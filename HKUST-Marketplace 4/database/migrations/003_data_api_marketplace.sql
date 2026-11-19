-- Public Neon Data API adapter for the JavaFX client.
--
-- Neon Auth owns password verification and sessions.  This function is the
-- only application surface exposed through the Data API; the marketplace
-- tables remain private and credentials are never returned to the client.

alter table app_users add column if not exists auth_user_id text;

create unique index if not exists app_users_auth_user_id_idx
    on app_users (auth_user_id)
    where auth_user_id is not null;

ALTER TABLE public.listings
    ADD COLUMN IF NOT EXISTS removal_reason text;

create or replace function public.marketplace_api(
    p_action text,
    p_payload jsonb default '{}'::jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_action text := lower(coalesce(p_action, ''));
    v_payload jsonb := coalesce(p_payload, '{}'::jsonb);
    v_claims jsonb := coalesce(nullif(current_setting('request.jwt.claims', true), '')::jsonb, '{}'::jsonb);
    v_auth_id text := nullif(v_claims ->> 'sub', '');
    v_email text := lower(nullif(v_claims ->> 'email', ''));
    v_username text;
    v_role text;
    v_status text;
    v_seller text;
    v_buyer text;
    v_type text;
    v_id bigint;
    v_seller_id bigint;
    v_result jsonb;
    v_active boolean;
    v_existing app_users%rowtype;
    v_actor app_users%rowtype;
begin
    if v_action = 'check_username' then
        v_username := lower(trim(coalesce(v_payload ->> 'username', '')));
        if v_username = '' then
            return jsonb_build_object('ok', true, 'data', jsonb_build_object('available', false));
        end if;
        return jsonb_build_object(
            'ok', true,
            'data', jsonb_build_object(
                'available', not exists (
                    select 1 from app_users where lower(username) = v_username
                )
            )
        );
    end if;

    if v_auth_id is null then
        raise exception 'Authentication required';
    end if;

    if v_action = 'ensure_profile' then
        v_username := split_part(coalesce(v_email, ''), '@', 1);
        if v_username !~ '^[a-z0-9_.-]{3,50}$' then
            raise exception 'The authentication email does not contain a valid username';
        end if;
        select * into v_existing
          from app_users
         where auth_user_id = v_auth_id or lower(username) = v_username
         order by (auth_user_id = v_auth_id) desc
         limit 1;
        if found then
            if v_existing.auth_user_id is not null and v_existing.auth_user_id <> v_auth_id then
                raise exception 'Username is already linked to another account';
            end if;
            if v_existing.auth_user_id is null then
                update app_users set auth_user_id = v_auth_id, updated_at = now()
                 where id = v_existing.id;
            end if;
        else
            v_role := upper(coalesce(v_payload ->> 'role', 'BUYER'));
            if v_role not in ('BUYER', 'SELLER') then
                raise exception 'Only buyer and seller accounts can self-register';
            end if;
            if nullif(trim(v_payload ->> 'fullName'), '') is null
               or length(trim(v_payload ->> 'fullName')) > 120 then
                raise exception 'A valid full name is required';
            end if;
            insert into app_users
                (auth_user_id, username, full_name, role, residence_hall, bio, preferred_meetup)
            values
                (v_auth_id, v_username, trim(v_payload ->> 'fullName'), v_role::app_user_role,
                 nullif(trim(v_payload ->> 'residenceHall'), ''),
                 nullif(trim(v_payload ->> 'bio'), ''),
                 nullif(trim(v_payload ->> 'preferredMeetup'), ''))
            returning * into v_existing;
        end if;
        select jsonb_build_object(
            'username', u.username, 'fullName', u.full_name, 'role', u.role::text,
            'residenceHall', u.residence_hall, 'bio', u.bio,
            'preferredMeetup', u.preferred_meetup, 'active', u.is_active,
            'averageRating', u.average_rating, 'ratingCount', u.rating_count,
            'createdAt', u.created_at
        ) into v_result from app_users u where u.id = v_existing.id;
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;

    select * into v_actor from app_users
     where auth_user_id = v_auth_id and is_active = true;
    if not found then
        raise exception 'Marketplace profile is not linked to this account';
    end if;

    if v_action = 'me' or v_action = 'user' then
        if v_action = 'user' then
            v_username := lower(trim(coalesce(v_payload ->> 'username', '')));
            if v_actor.role <> 'ADMIN'::app_user_role and lower(v_actor.username) <> v_username then
                raise exception 'You can only view your own profile';
            end if;
            select * into v_existing from app_users where lower(username) = v_username;
            if not found then raise exception 'User not found'; end if;
        else
            v_existing := v_actor;
        end if;
        select jsonb_build_object(
            'username', v_existing.username, 'fullName', v_existing.full_name,
            'role', v_existing.role::text, 'residenceHall', v_existing.residence_hall,
            'bio', v_existing.bio, 'preferredMeetup', v_existing.preferred_meetup,
            'active', v_existing.is_active, 'averageRating', v_existing.average_rating,
            'ratingCount', v_existing.rating_count, 'createdAt', v_existing.created_at
        ) into v_result;
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;

    if v_action = 'users' then
        if v_actor.role <> 'ADMIN'::app_user_role then raise exception 'Administrator access required'; end if;
        v_role := nullif(upper(trim(v_payload ->> 'role')), '');
        if v_role is not null and v_role not in ('BUYER', 'SELLER', 'ADMIN') then
            raise exception 'Invalid user role';
        end if;
        select coalesce(jsonb_agg(jsonb_build_object(
            'username', u.username, 'fullName', u.full_name, 'role', u.role::text,
            'residenceHall', u.residence_hall, 'bio', u.bio,
            'preferredMeetup', u.preferred_meetup, 'active', u.is_active,
            'averageRating', u.average_rating, 'ratingCount', u.rating_count,
            'createdAt', u.created_at
        ) order by lower(u.username)), '[]'::jsonb)
          into v_result
          from app_users u
         where v_role is null or u.role::text = v_role;
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;

        -- Admin: update a buyer or seller profile.
    if v_action = 'admin_update_user' then
        if v_actor.role <> 'ADMIN'::app_user_role then
            raise exception 'Administrator access required';
    end if;
        v_username := lower(trim(coalesce(v_payload ->> 'username', '')));
        if v_username = '' then
            raise exception 'Username is required';
    end if;
    select * into v_existing
    from app_users
    where lower(username) = v_username;
    if not found then
                raise exception 'User not found';
    end if;
            if v_existing.role = 'ADMIN'::app_user_role then
                raise exception 'Admin accounts cannot be edited here';
    end if;
            if nullif(trim(v_payload ->> 'fullName'), '') is null
               or length(trim(v_payload ->> 'fullName')) > 120 then
                raise exception 'A valid full name is required';
    end if;
    update app_users
    set full_name = trim(v_payload ->> 'fullName'),
        residence_hall = nullif(trim(v_payload ->> 'residenceHall'), ''),
        bio = nullif(trim(v_payload ->> 'bio'), ''),
        preferred_meetup = nullif(trim(v_payload ->> 'preferredMeetup'), ''),
        updated_at = now()
    where id = v_existing.id
        returning * into v_existing;
    select jsonb_build_object(
                   'username', u.username,
                   'fullName', u.full_name,
                   'role', u.role::text,
                   'residenceHall', u.residence_hall,
                   'bio', u.bio,
                   'preferredMeetup', u.preferred_meetup,
                   'active', u.is_active,
                   'averageRating', u.average_rating,
                   'ratingCount', u.rating_count,
                   'createdAt', u.created_at
           ) into v_result
    from app_users u
    where u.id = v_existing.id;
    return jsonb_build_object('ok', true, 'data', v_result);
    end if;

        -- Admin: activate or deactivate a buyer or seller account.
        if v_action = 'admin_set_user_active' then
            if v_actor.role <> 'ADMIN'::app_user_role then
                raise exception 'Administrator access required';
    end if;
            v_username := lower(trim(coalesce(v_payload ->> 'username', '')));
            if v_username = '' then
                raise exception 'Username is required';
    end if;
            if coalesce(lower(v_payload ->> 'active'), '') not in ('true', 'false') then
                raise exception 'A valid active status is required';
    end if;
            v_active := (v_payload ->> 'active')::boolean;
    select * into v_existing
    from app_users
    where lower(username) = v_username;
    if not found then
                raise exception 'User not found';
    end if;

            if v_existing.role = 'ADMIN'::app_user_role then
                raise exception 'Admin accounts cannot be deactivated here';
    end if;
    update app_users
    set is_active = v_active,
        updated_at = now()
    where id = v_existing.id;
    return jsonb_build_object(
            'ok', true,
            'data', jsonb_build_object(
                    'username', v_existing.username,
                    'active', v_active
                    )
           );
    end if;

    --Admin update self profile
    IF v_action = 'admin_update_profile' THEN
    -- Only an authenticated Admin can update this profile.
    IF v_actor.role <> 'ADMIN'::app_user_role THEN
        RAISE EXCEPTION 'Admin access required';
END IF;
    -- Validate Full Name.
    IF NULLIF(TRIM(v_payload->>'full_name'), '') IS NULL THEN
        RAISE EXCEPTION 'Full Name cannot be empty';
END IF;
    IF LENGTH(TRIM(v_payload->>'full_name')) > 120 THEN
        RAISE EXCEPTION 'Full Name cannot exceed 120 characters';
END IF;
    -- Prevent an Admin from updating another account.
    IF v_payload ? 'username'
       AND LOWER(TRIM(v_payload->>'username')) <> LOWER(v_actor.username) THEN
        RAISE EXCEPTION 'You can only update your own profile';
END IF;
    -- Update only the currently authenticated Admin.
UPDATE public.app_users
SET full_name = TRIM(v_payload->>'full_name'),
    updated_at = NOW()
WHERE id = v_actor.id
    RETURNING * INTO v_actor;
RETURN jsonb_build_object(
        'ok', true,
        'data', jsonb_build_object(
                'username', v_actor.username,
                'fullName', v_actor.full_name
                )
       );
END IF;

    if v_action = 'listings' then
        v_status := upper(coalesce(nullif(v_payload ->> 'status', ''), 'LIVE'));
        v_seller := nullif(lower(trim(v_payload ->> 'seller')), '');
        if v_seller is not null then
            if v_actor.role <> 'ADMIN'::app_user_role and lower(v_actor.username) <> v_seller then
                raise exception 'You can only view your own listings';
            end if;
        elsif v_status in ('ALL', 'PENDING_REVIEW') then
            if v_actor.role <> 'ADMIN'::app_user_role then raise exception 'Administrator access required'; end if;
        elsif v_status not in ('LIVE', '') then
            raise exception 'Invalid listing status';
        end if;
        select coalesce(jsonb_agg(jsonb_build_object(
            'id', 'L' || l.id, 'title', l.title, 'description', l.description,
            'category', l.category, 'type', l.type::text, 'status', l.status::text,
            'condition', l.item_condition, 'price', l.price,
            'lendingDays', l.lending_days, 'meetupLocation', l.meetup_location,
            'sellerUsername', u.username, 'sellerFullName', u.full_name,
            'postedAt', l.created_at, 'reviewedAt', l.reviewed_at,
            'rejectionReason', l.rejection_reason, 'removalReason', l.removal_reason,
            'viewCount', l.view_count,
            'photoPaths', coalesce((select jsonb_agg(lp.storage_path order by lp.sort_order)
                                    from listing_photos lp where lp.listing_id = l.id), '[]'::jsonb)
        ) order by l.created_at desc), '[]'::jsonb)
          into v_result
          from listings l join app_users u on u.id = l.seller_id
         where (v_seller is null or lower(u.username) = v_seller)
           and (v_seller is not null or v_status = 'ALL' or l.status::text = v_status);
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;

    if v_action = 'listing' then
        v_id := nullif(regexp_replace(coalesce(v_payload ->> 'id', ''), '^L', '', 'i'), '')::bigint;
        select l.status::text, l.seller_id into v_status, v_seller_id
          from listings l where l.id = v_id;
        if not found then raise exception 'Listing not found'; end if;
        if v_status <> 'LIVE'
           and v_actor.role <> 'ADMIN'::app_user_role
           and v_seller_id <> v_actor.id then
            raise exception 'Listing is not public';
        end if;
        select jsonb_build_object(
            'id', 'L' || l.id, 'title', l.title, 'description', l.description,
            'category', l.category, 'type', l.type::text, 'status', l.status::text,
            'condition', l.item_condition, 'price', l.price, 'lendingDays', l.lending_days,
            'meetupLocation', l.meetup_location, 'sellerUsername', u.username,
            'sellerFullName', u.full_name, 'postedAt', l.created_at,
            'reviewedAt', l.reviewed_at, 'rejectionReason', l.rejection_reason,
            'removalReason', l.removal_reason,
            'viewCount', l.view_count,
            'photoPaths', coalesce((select jsonb_agg(lp.storage_path order by lp.sort_order)
                                    from listing_photos lp where lp.listing_id = l.id), '[]'::jsonb)
        ) into v_result
          from listings l join app_users u on u.id = l.seller_id
         where l.id = v_id;
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;

            if v_action = 'create_listing' then
            if v_actor.role not in ('SELLER'::app_user_role, 'ADMIN'::app_user_role) then
                raise exception 'Seller access required';
    end if;

            -- Sellers can only create listings for themselves.
            -- Admins must select an active Seller account.
            if v_actor.role = 'ADMIN'::app_user_role then
                v_username := lower(trim(coalesce(v_payload ->> 'sellerUsername', '')));

                if v_username = '' then
                    raise exception 'Please select a seller';
    end if;

    select id into v_seller_id
    from app_users
    where lower(username) = v_username
      and role = 'SELLER'::app_user_role
                   and is_active = true;

    if not found then
                    raise exception 'Selected seller does not exist or is inactive';
    end if;
    else
                v_seller_id := v_actor.id;
    end if;

            v_type := upper(coalesce(v_payload ->> 'type', ''));
            if v_type not in ('SELL', 'LEND', 'GIVEAWAY') then
                raise exception 'Invalid listing type';
    end if;

    insert into listings
    (seller_id, title, description, category, type, status, item_condition,
     price, lending_days, meetup_location)
    values
        (v_seller_id, trim(v_payload ->> 'title'),
         nullif(trim(v_payload ->> 'description'), ''),
         trim(v_payload ->> 'category'), v_type::listing_type, 'PENDING_REVIEW',
         trim(v_payload ->> 'condition'),
         case when v_type = 'SELL' then nullif(v_payload ->> 'price', '')::numeric end,
         case when v_type = 'LEND' then nullif(v_payload ->> 'lendingDays', '')::integer end,
         trim(v_payload ->> 'meetupLocation'))
        returning id into v_id;

    v_payload := jsonb_build_object('id', 'L' || v_id);

            -- Return the complete listing DTO with the actual seller's details.
    select jsonb_build_object(
                   'id', 'L' || l.id, 'title', l.title, 'description', l.description,
                   'category', l.category, 'type', l.type::text, 'status', l.status::text,
                   'condition', l.item_condition, 'price', l.price, 'lendingDays', l.lending_days,
                   'meetupLocation', l.meetup_location, 'sellerUsername', u.username,
                   'sellerFullName', u.full_name, 'postedAt', l.created_at,
                   'reviewedAt', l.reviewed_at, 'rejectionReason', l.rejection_reason,
                   'removalReason', l.removal_reason,
                   'viewCount', l.view_count, 'photoPaths', '[]'::jsonb
           ) into v_result
    from listings l
             join app_users u on u.id = l.seller_id
    where l.id = v_id;

    return jsonb_build_object('ok', true, 'data', v_result);
    end if;

    if v_action = 'update_listing' then
        v_id := nullif(regexp_replace(coalesce(v_payload ->> 'id', ''), '^L', '', 'i'), '')::bigint;
        select seller_id into v_seller_id from listings where id = v_id;
        if not found then raise exception 'Listing not found'; end if;
        if v_actor.role <> 'ADMIN'::app_user_role and v_seller_id <> v_actor.id then
            raise exception 'You can only update your own listings';
        end if;
        v_type := upper(coalesce(v_payload ->> 'type', ''));
        v_status := upper(coalesce(v_payload ->> 'status', 'PENDING_REVIEW'));
        if v_type not in ('SELL', 'LEND', 'GIVEAWAY') then raise exception 'Invalid listing type'; end if;
        if v_status not in (
            'PENDING_REVIEW',
            'LIVE',
            'REJECTED',
            'RESERVED',
            'SOLD',
            'LENT',
            'GIVEN',
            'REMOVED'
        ) then
            raise exception 'Invalid listing status';
        end if;
        if v_actor.role <> 'ADMIN'::app_user_role then v_status := 'PENDING_REVIEW'; end if;
        update listings set title = trim(v_payload ->> 'title'),
            description = nullif(trim(v_payload ->> 'description'), ''), category = trim(v_payload ->> 'category'),
            type = v_type::listing_type, status = v_status::listing_status,
            item_condition = trim(v_payload ->> 'condition'),
            price = case when v_type = 'SELL' then nullif(v_payload ->> 'price', '')::numeric end,
            lending_days = case when v_type = 'LEND' then nullif(v_payload ->> 'lendingDays', '')::integer end,
            meetup_location = trim(v_payload ->> 'meetupLocation'),
            reviewed_at = case when v_actor.role = 'ADMIN' then nullif(v_payload ->> 'reviewedAt', '')::timestamptz end,
            rejection_reason = case when v_actor.role = 'ADMIN' then nullif(trim(v_payload ->> 'rejectionReason'), '') end,
            updated_at = now()
         where id = v_id;
        select jsonb_build_object(
            'id', 'L' || l.id, 'title', l.title, 'description', l.description,
            'category', l.category, 'type', l.type::text, 'status', l.status::text,
            'condition', l.item_condition, 'price', l.price, 'lendingDays', l.lending_days,
            'meetupLocation', l.meetup_location, 'sellerUsername', u.username,
            'sellerFullName', u.full_name, 'postedAt', l.created_at,
            'reviewedAt', l.reviewed_at, 'rejectionReason', l.rejection_reason,
            'removalReason', l.removal_reason,
            'viewCount', l.view_count, 'photoPaths', '[]'::jsonb
        ) into v_result from listings l join app_users u on u.id = l.seller_id where l.id = v_id;
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;


        if v_action = 'admin_remove_listing' then
            -- Only Admin can take down a listing.
            if v_actor.role <> 'ADMIN'::app_user_role then
                raise exception 'Administrator access required';
        end if;

            v_id := nullif(
                regexp_replace(coalesce(v_payload ->> 'id', ''), '^L', '', 'i'),
                ''
            )::bigint;

            -- Check whether the listing exists.
            if not exists (
                select 1
                from listings
                where id = v_id
            ) then
                raise exception 'Listing not found';
        end if;

            -- Only LIVE listings can be taken down.
            if not exists (
                select 1
                from listings
                where id = v_id
                  and status = 'LIVE'::listing_status
            ) then
                raise exception 'Only LIVE listings can be removed';
        end if;

            -- A removal reason is mandatory.
            if nullif(trim(coalesce(v_payload ->> 'reason', '')), '') is null then
                raise exception 'Removal reason is required';
        end if;

            -- Soft removal: preserve the listing record.
        update listings
        set status = 'REMOVED'::listing_status,
                removal_reason = trim(v_payload ->> 'reason'),
                updated_at = now()
        where id = v_id;

        -- Notify buyers who have an active (PENDING) offer.
        -- DISTINCT ensures each buyer receives only one notification.
        insert into notifications (
            recipient_id,
            title,
            message,
            category,
            is_read
        )
        select distinct
            d.buyer_id,
            'Listing Removed',
            'The listing "' || l.title
                || '" has been removed by the administrator. Reason: '
                || trim(v_payload ->> 'reason'),
            'System',
            false
        from deals d
                 join listings l
                      on l.id = d.listing_id
        where d.listing_id = v_id
          and d.status = 'PENDING'::deal_status;

        return jsonb_build_object(
                'ok', true,
                'data', jsonb_build_object('ok', true)
               );
        end if;

    if v_action = 'delete_listing' then
        v_id := nullif(regexp_replace(coalesce(v_payload ->> 'id', ''), '^L', '', 'i'), '')::bigint;
        select seller_id into v_seller_id from listings where id = v_id;
        if not found then raise exception 'Listing not found'; end if;
        if v_actor.role <> 'ADMIN'::app_user_role and v_seller_id <> v_actor.id then
            raise exception 'You can only delete your own listings';
        end if;
        delete from listings where id = v_id;
        return jsonb_build_object('ok', true, 'data', jsonb_build_object('ok', true));
    end if;

    if v_action = 'deals' then
        v_buyer := nullif(lower(trim(v_payload ->> 'buyer')), '');
        if v_buyer is null then
            if v_actor.role <> 'ADMIN'::app_user_role then raise exception 'Administrator access required'; end if;
        elsif v_actor.role <> 'ADMIN'::app_user_role and lower(v_actor.username) <> v_buyer then
            raise exception 'You can only view your own deals';
        end if;
        select coalesce(jsonb_agg(jsonb_build_object(
            'id', 'D' || d.id, 'listingId', 'L' || d.listing_id, 'listingTitle', l.title,
            'buyerUsername', b.username, 'sellerUsername', s.username, 'dealType', d.deal_type::text,
            'status', initcap(lower(d.status::text)), 'offerPrice', d.offer_price,
            'borrowStart', d.borrow_start, 'borrowEnd', d.borrow_end, 'rated', d.rated,
            'createdAt', d.created_at, 'completedAt', d.completed_at
        ) order by d.created_at desc), '[]'::jsonb)
          into v_result from deals d join listings l on l.id = d.listing_id
          join app_users b on b.id = d.buyer_id join app_users s on s.id = d.seller_id
         where v_buyer is null or lower(b.username) = v_buyer;
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;

    if v_action = 'create_deal' then
        if v_actor.role <> 'BUYER'::app_user_role then raise exception 'Buyer access required'; end if;
        v_id := nullif(regexp_replace(coalesce(v_payload ->> 'listingId', ''), '^L', '', 'i'), '')::bigint;
        select seller_id, type into v_seller_id, v_type from listings where id = v_id and status = 'LIVE';
        if not found then raise exception 'Live listing not found'; end if;
        insert into deals (listing_id, buyer_id, seller_id, deal_type, status, offer_price, borrow_start, borrow_end)
        values (v_id, v_actor.id, v_seller_id, v_type::listing_type, 'PENDING',
                nullif(v_payload ->> 'offerPrice', '')::numeric,
                nullif(v_payload ->> 'borrowStart', '')::date,
                nullif(v_payload ->> 'borrowEnd', '')::date)
        returning deals.id into v_id;
        select jsonb_build_object(
            'id', 'D' || d.id, 'listingId', 'L' || d.listing_id, 'listingTitle', l.title,
            'buyerUsername', b.username, 'sellerUsername', s.username, 'dealType', d.deal_type::text,
            'status', initcap(lower(d.status::text)), 'offerPrice', d.offer_price,
            'borrowStart', d.borrow_start, 'borrowEnd', d.borrow_end, 'rated', d.rated,
            'createdAt', d.created_at, 'completedAt', d.completed_at
        ) into v_result from deals d join listings l on l.id = d.listing_id
          join app_users b on b.id = d.buyer_id join app_users s on s.id = d.seller_id where d.id = v_id;
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;

    if v_action = 'notifications' then
        v_username := lower(coalesce(nullif(trim(v_payload ->> 'username'), ''), v_actor.username));
        if v_actor.role <> 'ADMIN'::app_user_role and v_username <> lower(v_actor.username) then
            raise exception 'You can only view your own notifications';
        end if;
        select coalesce(jsonb_agg(jsonb_build_object(
            'id', 'N' || n.id, 'username', u.username, 'title', n.title, 'message', n.message,
            'category', n.category, 'timestamp', n.created_at, 'read', n.is_read
        ) order by n.created_at desc), '[]'::jsonb)
          into v_result from notifications n join app_users u on u.id = n.recipient_id
         where lower(u.username) = v_username;
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;

    if v_action = 'create_notification' then
        if v_actor.role <> 'ADMIN'::app_user_role then raise exception 'Administrator access required'; end if;
        v_username := lower(trim(v_payload ->> 'username'));
        select id into v_id from app_users where lower(username) = v_username;
        if not found then raise exception 'Notification recipient not found'; end if;
        insert into notifications (recipient_id, title, message, category, is_read)
        values (v_id, trim(v_payload ->> 'title'), trim(v_payload ->> 'message'),
                trim(v_payload ->> 'category'), coalesce((v_payload ->> 'read')::boolean, false))
        returning id into v_id;
        select jsonb_build_object(
            'id', 'N' || n.id, 'username', u.username, 'title', n.title, 'message', n.message,
            'category', n.category, 'timestamp', n.created_at, 'read', n.is_read
        ) into v_result from notifications n join app_users u on u.id = n.recipient_id where n.id = v_id;
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;

    if v_action = 'reviews' then
        v_seller := nullif(lower(trim(v_payload ->> 'seller')), '');
        if v_seller is null and v_actor.role <> 'ADMIN'::app_user_role then raise exception 'Administrator access required'; end if;
        select coalesce(jsonb_agg(jsonb_build_object(
            'id', 'R' || r.id, 'sellerUsername', s.username, 'buyerUsername', b.username,
            'listingTitle', l.title, 'rating', r.rating, 'comment', r.comment,
            'sellerReply', r.seller_reply, 'hidden', r.is_hidden, 'createdAt', r.created_at
        ) order by r.created_at desc), '[]'::jsonb)
          into v_result from reviews r join listings l on l.id = r.listing_id
          join app_users s on s.id = r.seller_id join app_users b on b.id = r.buyer_id
         where (v_seller is null or lower(s.username) = v_seller)
           and (v_seller is null or r.is_hidden = false);
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;

    if v_action = 'create_review' then
        if v_actor.role <> 'BUYER'::app_user_role then raise exception 'Buyer access required'; end if;
        v_id := nullif(regexp_replace(coalesce(v_payload ->> 'listingId', ''), '^L', '', 'i'), '')::bigint;
        select seller_id into v_seller_id from listings where id = v_id;
        if not found then raise exception 'Listing not found'; end if;
        insert into reviews (listing_id, seller_id, buyer_id, rating, comment)
        values (v_id, v_seller_id, v_actor.id,
                (v_payload ->> 'rating')::integer, trim(v_payload ->> 'comment'))
        returning id into v_id;
        select jsonb_build_object(
            'id', 'R' || r.id, 'sellerUsername', s.username, 'buyerUsername', b.username,
            'listingTitle', l.title, 'rating', r.rating, 'comment', r.comment,
            'sellerReply', r.seller_reply, 'hidden', r.is_hidden, 'createdAt', r.created_at
        ) into v_result from reviews r join listings l on l.id = r.listing_id
          join app_users s on s.id = r.seller_id join app_users b on b.id = r.buyer_id where r.id = v_id;
        return jsonb_build_object('ok', true, 'data', v_result);
    end if;

    raise exception 'Unknown marketplace action';
end;
$$;

revoke all on function public.marketplace_api(text, jsonb) from public;
grant execute on function public.marketplace_api(text, jsonb) to anonymous, authenticated;

