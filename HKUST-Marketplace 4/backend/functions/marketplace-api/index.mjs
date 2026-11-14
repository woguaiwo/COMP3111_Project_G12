import pg from "pg";
import {
  createHmac,
  pbkdf2Sync,
  randomBytes,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";

const { Pool } = pg;
const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: 5,
  idleTimeoutMillis: 10_000,
  connectionTimeoutMillis: 10_000,
});

const JWT_SECRET = process.env.JWT_SECRET;
const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;
const PBKDF2_ITERATIONS = 120_000;
const PBKDF2_KEY_BYTES = 32;

if (!JWT_SECRET) {
  throw new Error("JWT_SECRET is required");
}

export default async function handler(request) {
  const url = new URL(request.url);
  const method = request.method.toUpperCase();

  if (method === "OPTIONS") {
    return response(null, 204);
  }

  try {
    if (method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "marketplace-api" });
    }

    if (url.pathname === "/auth/check-username" && method === "GET") {
      const username = normalizeUsername(url.searchParams.get("username"));
      if (!username) return json({ available: false });
      const result = await pool.query(
        "select 1 from app_users where lower(username) = lower($1) limit 1",
        [username],
      );
      return json({ available: result.rowCount === 0 });
    }

    if (url.pathname === "/auth/register" && method === "POST") {
      return await register(await readJson(request));
    }

    if (url.pathname === "/auth/login" && method === "POST") {
      return await login(await readJson(request));
    }

    const session = await authenticateRequest(request);

    if (url.pathname === "/auth/logout" && method === "POST") {
      await pool.query("update app_sessions set revoked_at = now() where jti = $1", [session.jti]);
      return json({ ok: true });
    }

    if (url.pathname === "/auth/me" && method === "GET") {
      return json({ user: userDto(session.user) });
    }

    if (url.pathname === "/api/users" && method === "GET") {
      requireRole(session, "ADMIN");
      const role = url.searchParams.get("role");
      const values = [];
      let where = "";
      if (role) {
        validateRole(role);
        values.push(role.toUpperCase());
        where = "where role = $1::app_user_role";
      }
      const result = await pool.query(
        `select id, username, full_name, role::text as role_name,
                residence_hall, bio, preferred_meetup, is_active,
                average_rating, rating_count, created_at
           from app_users ${where} order by lower(username)`,
        values,
      );
      return json(result.rows.map(userDto));
    }

    const userMatch = url.pathname.match(/^\/api\/users\/([^/]+)$/);
    if (userMatch && method === "GET") {
      const username = decodeURIComponent(userMatch[1]).toLowerCase();
      if (session.user.role !== "ADMIN" && session.user.username !== username) {
        throw new HttpError(403, "You can only view your own profile");
      }
      const result = await pool.query(userQuery() + " where lower(username) = lower($1)", [username]);
      if (result.rowCount === 0) throw new HttpError(404, "User not found");
      return json(userDto(result.rows[0]));
    }

    if (url.pathname === "/api/listings" && method === "GET") {
      return await getListings(url, session);
    }

    const listingMatch = url.pathname.match(/^\/api\/listings\/([^/]+)$/);
    if (listingMatch) {
      const id = numericId(listingMatch[1], "L");
      if (id === null) throw new HttpError(400, "Invalid listing id");
      if (method === "GET") return await getListing(id, session);
      if (method === "PUT") return await updateListing(id, await readJson(request), session);
      if (method === "DELETE") return await deleteListing(id, session);
    }

    if (url.pathname === "/api/listings" && method === "POST") {
      return await createListing(await readJson(request), session);
    }

    if (url.pathname === "/api/deals" && method === "GET") {
      return await getDeals(url, session);
    }
    if (url.pathname === "/api/deals" && method === "POST") {
      return await createDeal(await readJson(request), session);
    }

    if (url.pathname === "/api/notifications" && method === "GET") {
      const username = (url.searchParams.get("username") || session.user.username).toLowerCase();
      if (session.user.role !== "ADMIN" && username !== session.user.username) {
        throw new HttpError(403, "You can only view your own notifications");
      }
      const result = await pool.query(
        `${notificationQuery()} where lower(u.username) = lower($1) order by n.created_at desc`,
        [username],
      );
      return json(result.rows.map(notificationDto));
    }
    if (url.pathname === "/api/notifications" && method === "POST") {
      return await createNotification(await readJson(request), session);
    }

    if (url.pathname === "/api/reviews" && method === "GET") {
      const seller = url.searchParams.get("seller");
      if (!seller && session.user.role !== "ADMIN") requireRole(session, "ADMIN");
      const result = await pool.query(
        `${reviewQuery()} ${seller ? "where lower(s.username) = lower($1)" : ""}
         ${seller ? "and" : "where"} ${seller ? "r.is_hidden = false" : "true"}
         order by r.created_at desc`,
        seller ? [seller] : [],
      );
      return json(result.rows.map(reviewDto));
    }
    if (url.pathname === "/api/reviews" && method === "POST") {
      return await createReview(await readJson(request), session);
    }

    throw new HttpError(404, "Route not found");
  } catch (error) {
    if (error instanceof HttpError) return json({ error: error.message }, error.status);
    console.error(error);
    return json({ error: "Internal server error" }, 500);
  }
}

async function register(body) {
  const username = normalizeUsername(body.username);
  const fullName = text(body.fullName);
  const password = typeof body.password === "string" ? body.password : "";
  const role = String(body.role || "").toUpperCase();

  validateUsername(username);
  if (!fullName || fullName.length > 120) throw new HttpError(400, "A valid full name is required");
  validatePassword(password);
  if (role !== "BUYER" && role !== "SELLER") {
    throw new HttpError(400, "Only buyer and seller accounts can self-register");
  }

  const client = await pool.connect();
  try {
    await client.query("begin");
    const userResult = await client.query(
      `insert into app_users
         (username, full_name, role, residence_hall, bio, preferred_meetup)
       values ($1, $2, $3::app_user_role, $4, $5, $6)
       returning id, username, full_name, role::text as role_name,
                 residence_hall, bio, preferred_meetup, is_active,
                 average_rating, rating_count, created_at`,
      [username, fullName, role, nullable(body.residenceHall), nullable(body.bio), nullable(body.preferredMeetup)],
    );
    const user = userResult.rows[0];
    await client.query(
      "insert into app_credentials (user_id, password_hash) values ($1, $2)",
      [user.id, hashPassword(password)],
    );
    await client.query("commit");
    return json({ user: userDto(user) }, 201);
  } catch (error) {
    await client.query("rollback");
    if (error.code === "23505") throw new HttpError(409, "Username already taken");
    throw error;
  } finally {
    client.release();
  }
}

async function login(body) {
  const username = normalizeUsername(body.username);
  const password = typeof body.password === "string" ? body.password : "";
  const result = await pool.query(
    `select u.id, u.username, u.full_name, u.role::text as role_name,
            u.residence_hall, u.bio, u.preferred_meetup, u.is_active,
            u.average_rating, u.rating_count, u.created_at,
            c.password_hash
       from app_users u
       join app_credentials c on c.user_id = u.id
      where lower(u.username) = lower($1) and u.is_active = true`,
    [username],
  );
  if (result.rowCount === 0 || !verifyPassword(password, result.rows[0].password_hash)) {
    throw new HttpError(401, "Invalid username or password");
  }

  const user = result.rows[0];
  const jti = randomUUID();
  const issuedAt = Math.floor(Date.now() / 1000);
  const expiresAt = issuedAt + ACCESS_TOKEN_TTL_SECONDS;
  await pool.query(
    "insert into app_sessions (jti, user_id, expires_at) values ($1, $2, to_timestamp($3))",
    [jti, user.id, expiresAt],
  );
  const token = signJwt({
    sub: String(user.id),
    username: user.username,
    role: user.role_name,
    jti,
    iat: issuedAt,
    exp: expiresAt,
  });
  return json({ token, expiresAt, user: userDto(user) });
}

async function authenticateRequest(request) {
  const header = request.headers.get("authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match) throw new HttpError(401, "Authentication required");
  const claims = verifyJwt(match[1]);
  const result = await pool.query(
    `${userQuery()}
       join app_sessions s on s.user_id = u.id
      where s.jti = $1 and s.revoked_at is null and s.expires_at > now()
        and u.is_active = true`,
    [claims.jti],
  );
  if (result.rowCount === 0) throw new HttpError(401, "Session expired or revoked");
  return { jti: claims.jti, user: result.rows[0] };
}

async function getListings(url, session) {
  const status = (url.searchParams.get("status") || "").toUpperCase();
  const seller = url.searchParams.get("seller");
  const values = [];
  const filters = [];

  if (seller) {
    if (session.user.role !== "ADMIN" && session.user.username !== seller.toLowerCase()) {
      throw new HttpError(403, "You can only view your own listings");
    }
    values.push(seller);
    filters.push(`lower(u.username) = lower($${values.length})`);
  } else if (status === "ALL" || status === "PENDING_REVIEW") {
    requireRole(session, "ADMIN");
    if (status === "PENDING_REVIEW") filters.push("l.status = 'PENDING_REVIEW'");
  } else if (status === "LIVE" || !status) {
    filters.push("l.status = 'LIVE'");
  } else {
    throw new HttpError(400, "Invalid listing status");
  }

  const result = await pool.query(
    `${listingQuery()} ${filters.length ? "where " + filters.join(" and ") : ""}
     order by l.created_at desc`,
    values,
  );
  return json(result.rows.map(listingDto));
}

async function getListing(id, session) {
  const result = await pool.query(`${listingQuery()} where l.id = $1`, [id]);
  if (result.rowCount === 0) throw new HttpError(404, "Listing not found");
  const listing = result.rows[0];
  if (listing.status_name !== "LIVE" && session.user.role !== "ADMIN" && listing.seller_username !== session.user.username) {
    throw new HttpError(403, "Listing is not public");
  }
  return json(listingDto(listing));
}

async function createListing(body, session) {
  requireRole(session, "SELLER", "ADMIN");
  const type = String(body.type || "").toUpperCase();
  if (!["SELL", "LEND", "GIVEAWAY"].includes(type)) throw new HttpError(400, "Invalid listing type");
  const result = await pool.query(
    `insert into listings
       (seller_id, title, description, category, type, status, item_condition,
        price, lending_days, meetup_location)
     values ($1, $2, $3, $4, $5::listing_type, 'PENDING_REVIEW', $6, $7, $8, $9)
     returning id`,
    [session.user.id, required(body.title, "title"), nullable(body.description),
      required(body.category, "category"), type, required(body.condition, "condition"),
      type === "SELL" ? numberOrNull(body.price) : null,
      type === "LEND" ? integerOrNull(body.lendingDays) : null,
      required(body.meetupLocation, "meetupLocation")],
  );
  return await getListing(result.rows[0].id, session);
}

async function updateListing(id, body, session) {
  const existing = await pool.query("select seller_id from listings where id = $1", [id]);
  if (existing.rowCount === 0) throw new HttpError(404, "Listing not found");
  if (session.user.role !== "ADMIN" && existing.rows[0].seller_id !== session.user.id) {
    throw new HttpError(403, "You can only update your own listings");
  }
  const type = String(body.type || "").toUpperCase();
  if (!["SELL", "LEND", "GIVEAWAY"].includes(type)) throw new HttpError(400, "Invalid listing type");
  const status = session.user.role === "ADMIN"
    ? String(body.status || "PENDING_REVIEW").toUpperCase()
    : "PENDING_REVIEW";
  if (!["PENDING_REVIEW", "LIVE", "SOLD", "REJECTED", "CLOSED"].includes(status)) {
    throw new HttpError(400, "Invalid listing status");
  }
  await pool.query(
    `update listings set title = $1, description = $2, category = $3,
        type = $4::listing_type, status = $5::listing_status,
        item_condition = $6, price = $7, lending_days = $8,
        meetup_location = $9, reviewed_at = $10, rejection_reason = $11,
        updated_at = now()
      where id = $12`,
    [required(body.title, "title"), nullable(body.description), required(body.category, "category"),
      type, status, required(body.condition, "condition"), type === "SELL" ? numberOrNull(body.price) : null,
      type === "LEND" ? integerOrNull(body.lendingDays) : null, required(body.meetupLocation, "meetupLocation"),
      session.user.role === "ADMIN" ? nullableDate(body.reviewedAt) : null,
      session.user.role === "ADMIN" ? nullable(body.rejectionReason) : null, id],
  );
  return await getListing(id, session);
}

async function deleteListing(id, session) {
  const existing = await pool.query("select seller_id from listings where id = $1", [id]);
  if (existing.rowCount === 0) throw new HttpError(404, "Listing not found");
  if (session.user.role !== "ADMIN" && existing.rows[0].seller_id !== session.user.id) {
    throw new HttpError(403, "You can only delete your own listings");
  }
  await pool.query("delete from listings where id = $1", [id]);
  return json({ ok: true });
}

async function getDeals(url, session) {
  const buyer = url.searchParams.get("buyer");
  if (buyer) {
    if (session.user.role !== "ADMIN" && session.user.username !== buyer.toLowerCase()) {
      throw new HttpError(403, "You can only view your own deals");
    }
  } else {
    requireRole(session, "ADMIN");
  }
  const values = buyer ? [buyer] : [];
  const where = buyer ? "where lower(b.username) = lower($1)" : "";
  const result = await pool.query(`${dealQuery()} ${where} order by d.created_at desc`, values);
  return json(result.rows.map(dealDto));
}

async function createDeal(body, session) {
  requireRole(session, "BUYER");
  const listingId = numericId(body.listingId, "L");
  if (listingId === null) throw new HttpError(400, "Invalid listing id");
  const listing = await pool.query(
    `select l.id, l.seller_id, l.type::text as type_name, l.price,
            u.username as seller_username
       from listings l join app_users u on u.id = l.seller_id
      where l.id = $1 and l.status = 'LIVE'`,
    [listingId],
  );
  if (listing.rowCount === 0) throw new HttpError(404, "Live listing not found");
  const row = listing.rows[0];
  const result = await pool.query(
    `insert into deals
       (listing_id, buyer_id, seller_id, deal_type, status, offer_price,
        borrow_start, borrow_end)
     values ($1, $2, $3, $4::listing_type, 'PENDING', $5, $6, $7)
     returning id`,
    [listingId, session.user.id, row.seller_id, row.type_name,
      numberOrNull(body.offerPrice), nullableDate(body.borrowStart), nullableDate(body.borrowEnd)],
  );
  const deal = await pool.query(`${dealQuery()} where d.id = $1`, [result.rows[0].id]);
  return json(dealDto(deal.rows[0]), 201);
}

async function createNotification(body, session) {
  const username = normalizeUsername(body.username);
  if (session.user.role !== "ADMIN" && username !== "admin") {
    throw new HttpError(403, "Only administrators can notify another user");
  }
  const recipient = await pool.query("select id from app_users where lower(username) = lower($1)", [username]);
  if (recipient.rowCount === 0) throw new HttpError(404, "Notification recipient not found");
  const result = await pool.query(
    `insert into notifications (recipient_id, title, message, category, is_read)
     values ($1, $2, $3, $4, $5) returning id`,
    [recipient.rows[0].id, required(body.title, "title"), required(body.message, "message"),
      required(body.category, "category"), Boolean(body.read)],
  );
  const notification = await pool.query(`${notificationQuery()} where n.id = $1`, [result.rows[0].id]);
  return json(notificationDto(notification.rows[0]), 201);
}

async function createReview(body, session) {
  requireRole(session, "BUYER");
  const listingId = numericId(body.listingId, "L");
  if (listingId === null) throw new HttpError(400, "Invalid listing id");
  const seller = await pool.query("select seller_id from listings where id = $1", [listingId]);
  if (seller.rowCount === 0) throw new HttpError(404, "Listing not found");
  const rating = Number(body.rating);
  if (!Number.isInteger(rating) || rating < 1 || rating > 5) throw new HttpError(400, "Rating must be 1 to 5");
  const result = await pool.query(
    `insert into reviews (listing_id, seller_id, buyer_id, rating, comment)
     values ($1, $2, $3, $4, $5) returning id`,
    [listingId, seller.rows[0].seller_id, session.user.id, rating, required(body.comment, "comment")],
  );
  const review = await pool.query(`${reviewQuery()} where r.id = $1`, [result.rows[0].id]);
  return json(reviewDto(review.rows[0]), 201);
}

function userQuery() {
  return `select u.id, u.username, u.full_name, u.role::text as role_name,
                 u.residence_hall, u.bio, u.preferred_meetup, u.is_active,
                 u.average_rating, u.rating_count, u.created_at
            from app_users u`;
}

function listingQuery() {
  return `select l.id, l.title, l.description, l.category, l.type::text as type_name,
                 l.status::text as status_name, l.item_condition, l.price,
                 l.lending_days, l.meetup_location, l.reviewed_at,
                 l.rejection_reason, l.view_count, l.created_at,
                 u.username as seller_username, u.full_name as seller_full_name
            from listings l join app_users u on u.id = l.seller_id`;
}

function dealQuery() {
  return `select d.id, d.listing_id, l.title as listing_title,
                 b.username as buyer_username, s.username as seller_username,
                 d.deal_type::text as deal_type_name, d.status::text as status_name,
                 d.offer_price, d.borrow_start, d.borrow_end, d.rated,
                 d.created_at, d.completed_at
            from deals d join listings l on l.id = d.listing_id
            join app_users b on b.id = d.buyer_id join app_users s on s.id = d.seller_id`;
}

function notificationQuery() {
  return `select n.id, u.username, n.title, n.message, n.category,
                 n.is_read, n.created_at
            from notifications n join app_users u on u.id = n.recipient_id`;
}

function reviewQuery() {
  return `select r.id, s.username as seller_username, b.username as buyer_username,
                 l.title as listing_title, r.rating, r.comment, r.seller_reply,
                 r.is_hidden, r.created_at
            from reviews r join listings l on l.id = r.listing_id
            join app_users s on s.id = r.seller_id join app_users b on b.id = r.buyer_id`;
}

function userDto(row) {
  return {
    username: row.username,
    fullName: row.full_name,
    role: row.role_name,
    residenceHall: row.residence_hall,
    bio: row.bio,
    preferredMeetup: row.preferred_meetup,
    active: row.is_active,
    averageRating: Number(row.average_rating || 0),
    ratingCount: Number(row.rating_count || 0),
    createdAt: toIso(row.created_at),
  };
}

function listingDto(row) {
  return {
    id: `L${row.id}`,
    title: row.title,
    description: row.description,
    category: row.category,
    type: row.type_name,
    status: row.status_name,
    condition: row.item_condition,
    price: row.price === null ? null : Number(row.price),
    lendingDays: row.lending_days,
    meetupLocation: row.meetup_location,
    sellerUsername: row.seller_username,
    sellerFullName: row.seller_full_name,
    postedAt: toIso(row.created_at),
    reviewedAt: toIso(row.reviewed_at),
    rejectionReason: row.rejection_reason,
    viewCount: Number(row.view_count || 0),
    photoPaths: [],
  };
}

function dealDto(row) {
  return {
    id: `D${row.id}`,
    listingId: `L${row.listing_id}`,
    listingTitle: row.listing_title,
    buyerUsername: row.buyer_username,
    sellerUsername: row.seller_username,
    dealType: row.deal_type_name,
    status: titleCase(row.status_name),
    offerPrice: row.offer_price === null ? null : Number(row.offer_price),
    borrowStart: row.borrow_start ? String(row.borrow_start).slice(0, 10) : null,
    borrowEnd: row.borrow_end ? String(row.borrow_end).slice(0, 10) : null,
    rated: Boolean(row.rated),
    createdAt: toIso(row.created_at),
    completedAt: toIso(row.completed_at),
  };
}

function notificationDto(row) {
  return {
    id: `N${row.id}`,
    username: row.username,
    title: row.title,
    message: row.message,
    category: row.category,
    timestamp: toIso(row.created_at),
    read: Boolean(row.is_read),
  };
}

function reviewDto(row) {
  return {
    id: `R${row.id}`,
    sellerUsername: row.seller_username,
    buyerUsername: row.buyer_username,
    listingTitle: row.listing_title,
    rating: Number(row.rating),
    comment: row.comment,
    sellerReply: row.seller_reply,
    hidden: Boolean(row.is_hidden),
    createdAt: toIso(row.created_at),
  };
}

function signJwt(payload) {
  const header = base64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const encodedPayload = base64url(JSON.stringify(payload));
  const data = `${header}.${encodedPayload}`;
  const signature = base64url(createHmac("sha256", JWT_SECRET).update(data).digest());
  return `${data}.${signature}`;
}

function verifyJwt(token) {
  const parts = String(token).split(".");
  if (parts.length !== 3) throw new HttpError(401, "Invalid access token");
  const [header, payload, signature] = parts;
  let claims;
  try {
    claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
  } catch {
    throw new HttpError(401, "Invalid access token");
  }
  const expected = createHmac("sha256", JWT_SECRET).update(`${header}.${payload}`).digest();
  const actual = Buffer.from(signature, "base64url");
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) {
    throw new HttpError(401, "Invalid access token");
  }
  if (!claims.jti || !claims.exp || claims.exp <= Math.floor(Date.now() / 1000)) {
    throw new HttpError(401, "Access token expired");
  }
  return claims;
}

function hashPassword(password) {
  const salt = randomBytes(16);
  const derived = pbkdf2Sync(password, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BYTES, "sha256");
  return `pbkdf2_sha256$${PBKDF2_ITERATIONS}$${salt.toString("base64")}$${derived.toString("base64")}`;
}

function verifyPassword(password, stored) {
  const parts = String(stored || "").split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2_sha256") return false;
  const iterations = Number(parts[1]);
  if (!Number.isInteger(iterations) || iterations < 1 || iterations > 2_000_000) return false;
  try {
    const salt = Buffer.from(parts[2], "base64");
    const expected = Buffer.from(parts[3], "base64");
    const actual = pbkdf2Sync(password, salt, iterations, expected.length, "sha256");
    return actual.length === expected.length && timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

function base64url(value) {
  return Buffer.from(value).toString("base64url");
}

function normalizeUsername(value) {
  return typeof value === "string" ? value.trim().toLowerCase() : "";
}

function validateUsername(username) {
  if (!/^[a-z0-9_.-]{3,50}$/.test(username)) {
    throw new HttpError(400, "Username must be 3-50 letters, digits, dot, dash or underscore");
  }
}

function validatePassword(password) {
  if (password.length < 8 || password.length > 200 ||
      !/[A-Z]/.test(password) || !/[a-z]/.test(password) || !/[0-9]/.test(password)) {
    throw new HttpError(400, "Password must be at least 8 characters with upper, lower and digit");
  }
}

function validateRole(role) {
  if (!["BUYER", "SELLER", "ADMIN"].includes(String(role).toUpperCase())) {
    throw new HttpError(400, "Invalid user role");
  }
}

function requireRole(session, ...roles) {
  if (!roles.includes(session.user.role)) throw new HttpError(403, "You do not have permission for this action");
}

function numericId(value, prefix) {
  const raw = String(value ?? "");
  const number = raw.toUpperCase().startsWith(prefix) ? raw.slice(prefix.length) : raw;
  return /^\d+$/.test(number) ? Number(number) : null;
}

function required(value, name) {
  const result = text(value);
  if (!result) throw new HttpError(400, `${name} is required`);
  return result;
}

function text(value) {
  return typeof value === "string" ? value.trim() : "";
}

function nullable(value) {
  const result = text(value);
  return result || null;
}

function numberOrNull(value) {
  if (value === null || value === undefined || value === "") return null;
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) throw new HttpError(400, "Invalid numeric value");
  return number;
}

function integerOrNull(value) {
  const number = numberOrNull(value);
  if (number !== null && !Number.isInteger(number)) throw new HttpError(400, "Expected an integer");
  return number;
}

function nullableDate(value) {
  if (value === null || value === undefined || value === "") return null;
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) throw new HttpError(400, "Invalid date");
  return String(value).slice(0, 10);
}

function toIso(value) {
  return value ? new Date(value).toISOString() : null;
}

function titleCase(value) {
  return String(value || "").toLowerCase().replace(/^./, (c) => c.toUpperCase());
}

async function readJson(request) {
  try {
    const value = await request.json();
    return value && typeof value === "object" ? value : {};
  } catch {
    throw new HttpError(400, "Request body must be valid JSON");
  }
}

function json(value, status = 200) {
  return response(JSON.stringify(value), status);
}

function response(body, status) {
  return new Response(body, {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "access-control-allow-origin": "*",
      "access-control-allow-headers": "authorization, content-type",
      "access-control-allow-methods": "GET, POST, PUT, DELETE, OPTIONS",
    },
  });
}

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
