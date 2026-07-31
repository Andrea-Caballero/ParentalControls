-- BLK-03 Slice 2 — pgTAP verification for migration 015_rpc_hardening.sql.
-- Asserts the catalog state the migration must produce against historical
-- migrations 001–014. Requires a live PostgreSQL with pgtap + the schema
-- already migrated. Local runtime execution was unavailable at authoring
-- time; static checks (parse + plan count + signature symmetry) passed.

begin;
create extension if not exists pgtap with schema extensions;
set search_path = public, extensions;
select plan(71);

-- ---------- fixtures -------------------------------------------------------
insert into auth.users (id, email) values
  ('10000000-0000-0000-0000-000000000001', 'parent-a@example.test'),
  ('10000000-0000-0000-0000-000000000002', 'parent-b@example.test');
insert into children (id, parent_id, first_name) values
  ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'A'),
  ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'B');
insert into devices (id, device_name, parent_id, app_version, child_id) values
  ('40000000-0000-0000-0000-000000000001', 'device-a', '10000000-0000-0000-0000-000000000001', '1', '30000000-0000-0000-0000-000000000001'),
  ('40000000-0000-0000-0000-000000000002', 'device-b', '10000000-0000-0000-0000-000000000002', '1', '30000000-0000-0000-0000-000000000002');
insert into parent_outcome_checkins
  (parent_id, device_id, rating, period_start, period_end) values
  ('10000000-0000-0000-0000-000000000002',
   '40000000-0000-0000-0000-000000000002',
   'POSITIVE', current_date - 14, current_date);
insert into time_requests (id, device_id, minutes_requested, reason) values
  ('60000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 15, 'approve-me'),
  ('60000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', 10, 'reject-me');
insert into app_policies (device_id, package_name, state) values
  ('40000000-0000-0000-0000-000000000001', 'com.example.blocked', 'BLOCKED');

-- ---------- (A) signature inventory ----------------------------------------
select has_function('public', 'get_device_policy', ARRAY['uuid'],                  'get_device_policy signature');
select has_function('public', 'apply_policy_template', ARRAY['uuid','uuid'],       'apply_policy_template signature');
select has_function('public', 'bump_policy_version', ARRAY['uuid'],                'bump_policy_version signature');
select has_function('public', 'approve_request_atomic',
       ARRAY['uuid','uuid','integer','text'],                                         'approve_request_atomic signature');
select has_function('public', 'redeem_pairing_code_atomic',
       ARRAY['text','uuid','text','text','text','text','text','text'],                'redeem_pairing_code_atomic 8-arg signature');
select has_function('public', 'bump_policy_version_trigger', ARRAY[]::text[],      'bump_policy_version_trigger signature');
select has_function('public', 'is_device_user', ARRAY[]::text[],                   'is_device_user signature');
select has_function('public', 'is_parent_user', ARRAY[]::text[],                   'is_parent_user signature');
select has_function('public', 'get_available_templates', ARRAY[]::text[],          'get_available_templates signature');
select has_function('public', 'get_parent_checkins', ARRAY['uuid','integer'],      'get_parent_checkins signature');
select has_function('public', 'insert_parent_checkin',
       ARRAY['checkin_rating','date','date','text','uuid'],                           'insert_parent_checkin signature');
select has_function('public', 'update_updated_at', ARRAY[]::text[],                'update_updated_at signature');
select has_function('public', 'cleanup_expired_grants', ARRAY[]::text[],           'cleanup_expired_grants signature');
select has_function('public', 'cleanup_old_usage_logs', ARRAY['integer'],          'cleanup_old_usage_logs signature');
select has_function('public', 'cleanup_old_heartbeats', ARRAY['integer'],          'cleanup_old_heartbeats signature');
select has_function('public', 'cleanup_expired_pairing_codes', ARRAY[]::text[],    'cleanup_expired_pairing_codes signature');

-- ---------- (B) DEFINER / INVOKER shape ------------------------------------
select ok((select prosecdef from pg_proc where proname='get_device_policy'),            'get_device_policy is DEF');
select ok((select prosecdef from pg_proc where proname='apply_policy_template'),        'apply_policy_template is DEF');
select ok((select prosecdef from pg_proc where proname='bump_policy_version'),         'bump_policy_version is DEF');
select ok((select prosecdef from pg_proc where proname='approve_request_atomic'),       'approve_request_atomic is DEF');
select ok((select prosecdef from pg_proc where proname='redeem_pairing_code_atomic'),   'redeem_pairing_code_atomic is DEF');
select ok((select prosecdef from pg_proc where proname='bump_policy_version_trigger'), 'bump_policy_version_trigger is DEF');
select ok((select prosecdef from pg_proc where proname='is_device_user'),               'is_device_user is DEF');
select ok((select prosecdef from pg_proc where proname='is_parent_user'),               'is_parent_user is DEF');
select ok((select prosecdef from pg_proc where proname='get_available_templates'),      'get_available_templates is DEF');
select ok((select prosecdef from pg_proc where proname='update_updated_at'),            'update_updated_at is DEF');
select ok((select prosecdef from pg_proc where proname='cleanup_expired_grants'),       'cleanup_expired_grants is DEF');
select ok((select prosecdef from pg_proc where proname='cleanup_old_usage_logs'),       'cleanup_old_usage_logs is DEF');
select ok((select prosecdef from pg_proc where proname='cleanup_old_heartbeats'),       'cleanup_old_heartbeats is DEF');
select ok((select prosecdef from pg_proc where proname='cleanup_expired_pairing_codes'),'cleanup_expired_pairing_codes is DEF');
select ok(not (select prosecdef from pg_proc where proname='get_parent_checkins'),      'get_parent_checkins is INVOKER');
select ok(not (select prosecdef from pg_proc where proname='insert_parent_checkin'),    'insert_parent_checkin is INVOKER');

-- ---------- (C) safe search_path -------------------------------------------
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='get_device_policy'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'get_device_policy search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='apply_policy_template'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'apply_policy_template search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='bump_policy_version'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'bump_policy_version search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='approve_request_atomic'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'approve_request_atomic search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='redeem_pairing_code_atomic'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'redeem_pairing_code_atomic search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='bump_policy_version_trigger'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'bump_policy_version_trigger search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='is_device_user'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'is_device_user search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='is_parent_user'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'is_parent_user search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='get_available_templates'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'get_available_templates search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='update_updated_at'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'update_updated_at search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='cleanup_expired_grants'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'cleanup_expired_grants search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='cleanup_old_usage_logs'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'cleanup_old_usage_logs search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='cleanup_old_heartbeats'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'cleanup_old_heartbeats search_path');
select results_eq(
  $$select array_to_string(proconfig,',') COLLATE "C" from pg_proc where proname='cleanup_expired_pairing_codes'$$,
  $$values ('search_path=pg_catalog, public, pg_temp' COLLATE "C")$$, 'cleanup_expired_pairing_codes search_path');
select ok(
  (select array_to_string(proconfig,',') like 'search_path=%'
     from pg_proc where proname='get_parent_checkins'),
  'get_parent_checkins pins search_path');
select ok(
  (select array_to_string(proconfig,',') like 'search_path=%'
     from pg_proc where proname='insert_parent_checkin'),
  'insert_parent_checkin pins search_path');

-- ---------- (D) service-only RPC EXECUTE matrix (catalog-driven) -----------
-- One assertion per function × four roles. pg_cron helpers, trigger helpers
-- and dead helpers are reachable only by postgres (pg_cron or trigger
-- execution), never by an API role.
select results_eq(
  $$SELECT n.nspname || '.' || p.proname || '(' ||
          array_to_string(ARRAY(SELECT format_type(t, NULL)
                                  FROM unnest(p.proargtypes) AS t), ',')
          || ')' COLLATE "C" AS sig,
          has_function_privilege('service_role',   p.oid, 'EXECUTE') AS has_sv,
          has_function_privilege('postgres',      p.oid, 'EXECUTE') AS has_pg,
          has_function_privilege('anon',          p.oid, 'EXECUTE') AS has_an,
          has_function_privilege('authenticated', p.oid, 'EXECUTE') AS has_au
     FROM pg_proc p
     JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname IN (
        'get_device_policy','apply_policy_template','bump_policy_version',
        'approve_request_atomic','redeem_pairing_code_atomic',
        'cleanup_expired_grants','cleanup_old_usage_logs',
        'cleanup_old_heartbeats','cleanup_expired_pairing_codes',
        'bump_policy_version_trigger','is_device_user','is_parent_user',
        'get_available_templates')
    ORDER BY sig$$,
  $$VALUES
    ('public.apply_policy_template(uuid,uuid)' COLLATE "C",                true,  true,  false, false),
    ('public.approve_request_atomic(uuid,uuid,integer,text)' COLLATE "C", true,  true,  false, false),
    ('public.bump_policy_version(uuid)' COLLATE "C",                       true,  true,  false, false),
    ('public.bump_policy_version_trigger()' COLLATE "C",                   false, true,  false, false),
    ('public.cleanup_expired_grants()' COLLATE "C",                        false, true,  false, false),
    ('public.cleanup_expired_pairing_codes()' COLLATE "C",                 false, true,  false, false),
    ('public.cleanup_old_heartbeats(integer)' COLLATE "C",                 false, true,  false, false),
    ('public.cleanup_old_usage_logs(integer)' COLLATE "C",                 false, true,  false, false),
    ('public.get_available_templates()' COLLATE "C",                       false, true,  false, false),
    ('public.get_device_policy(uuid)' COLLATE "C",                         true,  true,  false, false),
    ('public.is_device_user()' COLLATE "C",                                false, true,  false, false),
    ('public.is_parent_user()' COLLATE "C",                                false, true,  false, false),
    ('public.redeem_pairing_code_atomic(text,uuid,text,text,text,text,text,text)' COLLATE "C", true, true, false, false)
  $$,
  'service-only RPC EXECUTE matrix (13 hardened fns x 4 roles)');

-- ---------- (E) parent RPC EXECUTE matrix ----------------------------------
select results_eq(
  $$SELECT n.nspname || '.' || p.proname || '(' ||
          array_to_string(ARRAY(SELECT format_type(t, NULL)
                                  FROM unnest(p.proargtypes) AS t), ',')
          || ')' AS sig,
          has_function_privilege('authenticated', p.oid, 'EXECUTE') AS has_au,
          has_function_privilege('anon',         p.oid, 'EXECUTE') AS has_an
     FROM pg_proc p
     JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname IN ('get_parent_checkins','insert_parent_checkin')
    ORDER BY sig$$,
  $$VALUES
    ('public.get_parent_checkins(uuid,integer)' COLLATE "C",                          true,  false),
    ('public.insert_parent_checkin(checkin_rating,date,date,text,uuid)' COLLATE "C",  true,  false)
  $$,
  'parent RPC EXECUTE matrix');

-- ---------- (F) parent-facing cross-parent behaviour -----------------------
reset role;
select set_config('request.jwt.claims',
  '{"sub":"10000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
set local role authenticated;

select is(
  (select count(*)::int from public.get_parent_checkins(
      '10000000-0000-0000-0000-000000000001'::uuid, 12)),
  0, 'parent A sees zero checkins before insert');

select throws_ok(
  $$select * from public.get_parent_checkins(
      '10000000-0000-0000-0000-000000000002'::uuid, 12)$$,
  '42501', null, 'parent A cannot read parent B checkins');

select lives_ok(
  $$select * from public.insert_parent_checkin('POSITIVE'::public.checkin_rating,
      current_date - 14, current_date, 'own', '40000000-0000-0000-0000-000000000001'::uuid)$$,
  'parent A inserts own checkin');

-- Positive visibility: parent A MUST observe the inserted own row, not a
-- zero-row RLS filter. Asserts both count and identity.
select is(
  (select count(*)::int from public.get_parent_checkins(
      '10000000-0000-0000-0000-000000000001'::uuid, 12)),
  1, 'parent A observes own inserted checkin (count = 1)');

select is(
  (select parent_id::text from public.get_parent_checkins(
      '10000000-0000-0000-0000-000000000001'::uuid, 12) limit 1),
  '10000000-0000-0000-0000-000000000001',
  'parent A inserted row is attributed to parent A');

select throws_ok(
  $$select * from public.insert_parent_checkin('NEGATIVE'::public.checkin_rating,
      current_date - 7, current_date, 'cross-device',
      '40000000-0000-0000-0000-000000000002'::uuid)$$,
  '42501', null, 'parent A cannot insert checkin targeting parent B device');

-- ---------- (G) schema public CREATE privilege ----------------------------
-- API roles cannot CREATE in `public`; only the owner/admin role retains
-- CREATE. The fixed search_path invariant is enforced only after this
-- REVOKE lands.
select ok(not has_schema_privilege('anon',         'public', 'CREATE'),
          'anon cannot CREATE in schema public');
select ok(not has_schema_privilege('authenticated','public', 'CREATE'),
          'authenticated cannot CREATE in schema public');
select ok(has_schema_privilege('postgres',    'public', 'CREATE'),
          'postgres retains CREATE in schema public');
select ok(not has_schema_privilege('service_role','public', 'CREATE'),
          'service_role does not retain CREATE in schema public');

-- ---------- (H) trigger helper structural integrity ------------------------
select has_function('public', 'update_updated_at', ARRAY[]::text[],
          'update_updated_at remains installed');
select ok((select count(*) >= 1 from pg_trigger t
           join pg_proc p on p.oid = t.tgfoid
           where p.proname = 'update_updated_at'),
           'update_updated_at remains wired to >= 1 trigger');

select ok(
  not has_function_privilege('anon', 'public.update_updated_at()', 'EXECUTE')
  and not has_function_privilege('authenticated', 'public.update_updated_at()', 'EXECUTE')
  and not has_function_privilege('service_role', 'public.update_updated_at()', 'EXECUTE'),
  'update_updated_at is not executable by API roles'
);

-- ---------- (I) runtime scenario coverage ---------------------------------
reset role;

set local role service_role;

select is(
  (
    with result as (
      select public.approve_request_atomic(
        '60000000-0000-0000-0000-000000000001'::uuid,
        '10000000-0000-0000-0000-000000000001'::uuid,
        15,
        'ok'
      ) as response
    )
    select response->>'decision' from result
  ),
  'APPROVED',
  'service-role approve_request_atomic succeeds for the owning parent'
);

select is(
  (select count(*)::int from grants where request_id = '60000000-0000-0000-0000-000000000001'::uuid),
  1,
  'approve_request_atomic inserts one grant on success'
);

select is(
  (
    with result as (
      select public.approve_request_atomic(
        '60000000-0000-0000-0000-000000000002'::uuid,
        '10000000-0000-0000-0000-000000000002'::uuid,
        15,
        'nope'
      ) as response
    )
    select response->>'code' from result
  ),
  'UNAUTHORIZED',
  'cross-parent verdict is rejected'
);

select is(
  (select count(*)::int from grants where request_id = '60000000-0000-0000-0000-000000000002'::uuid),
  0,
  'cross-parent verdict creates no grant'
);

reset role;
set local role authenticated;
select throws_ok(
  $$select public.redeem_pairing_code_atomic(
    'ABC23456',
    '00000000-0000-0000-0000-000000000001'::uuid,
    'Test Device',
    'P7',
    '34',
    '1.0.0',
    'Lucia',
    '7-12'
  )$$,
  '42501', null, 'direct API-role pairing redemption is rejected'
);

reset role;
select is(
  (select count(*)::int from devices),
  2,
  'pairing rejection does not add a second device'
);

reset role;
set local role authenticated;
select throws_ok(
  $$select public.apply_policy_template(
    '40000000-0000-0000-0000-000000000001'::uuid,
    (select id from policy_templates where age_band = '7-12' order by is_default desc, created_at asc limit 1)
  )$$,
  '42501', null, 'direct API-role policy mutation is rejected'
);

select is(
  (select count(*)::int from app_policies where device_id = '40000000-0000-0000-0000-000000000001'::uuid),
  1,
  'policy rejection does not change app_policies'
);

select * from finish();
rollback;
