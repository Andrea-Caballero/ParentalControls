begin;

create extension if not exists pgtap with schema extensions;
set search_path = public, extensions;
select plan(48);

insert into auth.users (id, email) values
  ('10000000-0000-0000-0000-000000000001', 'parent-a@example.test'),
  ('10000000-0000-0000-0000-000000000002', 'parent-b@example.test');
insert into children (id, parent_id, first_name) values
  ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'A'),
  ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'B');
insert into devices (id, device_name, parent_id, app_version, child_id) values
  ('40000000-0000-0000-0000-000000000001', 'device-a', '10000000-0000-0000-0000-000000000001', '1', '30000000-0000-0000-0000-000000000001'),
  ('40000000-0000-0000-0000-000000000002', 'device-b', '10000000-0000-0000-0000-000000000002', '1', '30000000-0000-0000-0000-000000000002');
insert into usage_logs (id, device_id, package_name, bucket_date, usage_minutes) values
  ('50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 'app.a', current_date, 1),
  ('50000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000002', 'app.b', current_date, 2);
insert into pairing_codes (id, code, parent_id, expires_at) values
  ('80000000-0000-0000-0000-000000000001', 'PARENTA1', '10000000-0000-0000-0000-000000000001', now() + interval '10 minutes'),
  ('80000000-0000-0000-0000-000000000002', 'PARENTB2', '10000000-0000-0000-0000-000000000002', now() + interval '10 minutes');

select set_config('request.jwt.claims', '{"sub":"20000000-0000-0000-0000-000000000001","role":"authenticated","device_id":"40000000-0000-0000-0000-000000000001"}', true);
set local role authenticated;
select results_eq(
  $$select id from devices order by id$$,
  $$values ('40000000-0000-0000-0000-000000000001'::uuid)$$,
  'device A selects only its own device row');
select throws_ok(
  $$insert into devices (id, device_name, parent_id, app_version) values ('40000000-0000-0000-0000-000000000004', 'attack', '10000000-0000-0000-0000-000000000002', '1')$$,
  '42501', null, 'device A cannot insert a device B row');
select lives_ok(
  $$insert into usage_logs (device_id, package_name, bucket_date, usage_minutes) values ('40000000-0000-0000-0000-000000000001', 'app.new', current_date, 3)$$,
  'device A inserts its own telemetry');
select throws_ok(
  $$insert into usage_logs (device_id, package_name, bucket_date, usage_minutes) values ('40000000-0000-0000-0000-000000000002', 'app.attack', current_date, 3)$$,
  '42501', null, 'device A cannot insert device B telemetry');
select is(
  (with changed as (update devices set child_id = null where id = '40000000-0000-0000-0000-000000000002' returning 1) select count(*) from changed),
  0::bigint, 'device A cannot update device B rows');
select throws_ok(
  $$delete from devices where id = '40000000-0000-0000-0000-000000000002'$$,
  '42501', null, 'device A cannot delete device B rows');
select throws_ok(
  $$update usage_logs set device_id = '40000000-0000-0000-0000-000000000002' where id = '50000000-0000-0000-0000-000000000001'$$,
  '42501', null, 'device cannot reassign a telemetry device_id');
select throws_ok(
  $$update devices set parent_id = '10000000-0000-0000-0000-000000000002' where id = '40000000-0000-0000-0000-000000000001'$$,
  '42501', null, 'device cannot reassign parent_id');
select lives_ok(
  $$insert into time_requests (device_id, minutes_requested, reason) values ('40000000-0000-0000-0000-000000000001', 10, 'test')$$,
  'device inserts its own time request');
select lives_ok(
  $$insert into behavioral_events (event_type, device_id, client_ts) values ('test', '40000000-0000-0000-0000-000000000001', now())$$,
  'device inserts its own behavioral telemetry');

-- Transition to parent-A identity with no device_id claim so the parent partition
-- is not contaminated by the device-A JWT from the prior partition.
reset role;
select set_config('request.jwt.claims', '{"sub":"10000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
set local role authenticated;
select results_eq(
  $$select id from devices order by id$$,
  $$values ('40000000-0000-0000-0000-000000000001'::uuid)$$,
  'parent A sees only parent A devices');
select results_eq(
  $$select id from children order by id$$,
  $$values ('30000000-0000-0000-0000-000000000001'::uuid)$$,
  'parent A sees only parent A children');
select lives_ok(
  $$update children set first_name = 'A renamed' where id = '30000000-0000-0000-0000-000000000001'$$,
  'parent can rename its own child');
select results_eq(
  $$select id from pairing_codes order by id$$,
  $$values ('80000000-0000-0000-0000-000000000001'::uuid)$$,
  'parent A sees only parent A pairing codes');
select throws_ok(
  $$update devices set child_id = '30000000-0000-0000-0000-000000000002' where id = '40000000-0000-0000-0000-000000000001'$$,
  '42501', null, 'parent cannot assign another parents child');
select lives_ok(
  $$update devices set child_id = '30000000-0000-0000-0000-000000000001' where id = '40000000-0000-0000-0000-000000000001'$$,
  'parent can assign its own child');
select throws_ok(
  $$update devices set device_state = 'LOCKED' where id = '40000000-0000-0000-0000-000000000001'$$,
  '42501', null, 'parent cannot mutate protected device columns');
select lives_ok(
  $$insert into app_policies (device_id, package_name, state) values ('40000000-0000-0000-0000-000000000001', 'app.managed', 'BLOCKED')$$,
  'parent inserts policy for its device');
select lives_ok(
  $$update app_policies set state = 'ALLOWED' where device_id = '40000000-0000-0000-0000-000000000001' and package_name = 'app.managed'$$,
  'parent updates policy for its device');
select lives_ok(
  $$delete from app_policies where device_id = '40000000-0000-0000-0000-000000000001' and package_name = 'app.managed'$$,
  'parent deletes policy for its device');
select lives_ok(
  $$insert into schedules (device_id, name, days, from_time, to_time, action) values ('40000000-0000-0000-0000-000000000001', 'bedtime', array['MONDAY'], '21:00', '07:00', 'LOCK')$$,
  'parent inserts schedule for its device');
select lives_ok(
  $$update schedules set is_active = false where device_id = '40000000-0000-0000-0000-000000000001'$$,
  'parent updates schedule for its device');
select lives_ok(
  $$delete from schedules where device_id = '40000000-0000-0000-0000-000000000001'$$,
  'parent deletes schedule for its device');
select throws_ok(
  $$insert into app_policies (device_id, package_name, state) values ('40000000-0000-0000-0000-000000000002', 'app.attack', 'BLOCKED')$$,
  '42501', null, 'parent A cannot manage parent B policy');
select throws_ok(
  $$update pairing_codes set status = 'CONSUMED' where id = '80000000-0000-0000-0000-000000000001'$$,
  '42501', null, 'pairing consumption is not parent writable');

reset role;
-- Non-owner subject (not parent-A, not parent-B) so neither device nor parent
-- policies expose rows when the device_id claim is malformed or missing.
select set_config('request.jwt.claims', '{"sub":"20000000-0000-0000-0000-000000000001","role":"authenticated","device_id":"not-a-uuid"}', true);
set local role authenticated;
select lives_ok($$select id from devices$$, 'malformed device_id claim denies without exception');
select is((select count(*) from devices), 0::bigint, 'malformed device_id claim sees no device rows');
select set_config('request.jwt.claims', '{"sub":"20000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select lives_ok($$select id from usage_logs$$, 'missing device_id claim denies without exception');
select is((select count(*) from usage_logs), 0::bigint, 'missing device_id claim sees no device rows');

reset role;
select set_config('request.jwt.claims', '{"sub":"90000000-0000-0000-0000-000000000001","role":"service_role"}', true);
set local role service_role;
select lives_ok(
  $$insert into devices (id, device_name, parent_id, app_version) values ('40000000-0000-0000-0000-000000000003', 'service-device', '10000000-0000-0000-0000-000000000001', '1')$$,
  'service_role creation bypass remains available');
select lives_ok(
  $$update pairing_codes set status = 'CONSUMED' where id = '80000000-0000-0000-0000-000000000001'$$,
  'service_role pairing consumption remains available');

reset role;
select ok(not exists (
  select 1 from pg_policy p join pg_class c on c.oid = p.polrelid
  where c.relname = any(array['devices','app_policies','grants','time_requests','usage_logs','device_push_tokens','device_heartbeats','schedules','outbox','children','pairing_codes','behavioral_events','device_alerts','integrity_reports'])
    and p.polcmd = '*'
), 'tenant tables have no FOR ALL policy');
select ok(not exists (
  select 1 from pg_policy p join pg_class c on c.oid = p.polrelid
  where c.relname = any(array['devices','app_policies','grants','time_requests','usage_logs','device_push_tokens','device_heartbeats','schedules','outbox','children','pairing_codes','behavioral_events','device_alerts','integrity_reports'])
    and p.polroles = '{0}'::oid[]
), 'tenant policies use explicit roles');
select ok((
  select bool_and(
    has_table_privilege('service_role', 'public.' || table_name, 'SELECT') and
    has_table_privilege('service_role', 'public.' || table_name, 'INSERT') and
    has_table_privilege('service_role', 'public.' || table_name, 'UPDATE') and
    has_table_privilege('service_role', 'public.' || table_name, 'DELETE'))
  from unnest(array['devices','children','pairing_codes','app_policies','grants','time_requests','usage_logs','device_push_tokens','device_heartbeats','schedules','outbox','behavioral_events','device_alerts','integrity_reports']) as table_name
), 'service_role retains table privileges');

select ok(not has_table_privilege('authenticated', 'public.devices', 'UPDATE'), 'authenticated lacks broad devices UPDATE');
select ok(has_column_privilege('authenticated', 'public.devices', 'child_id', 'UPDATE'), 'authenticated may update devices.child_id');
select ok(not has_column_privilege('authenticated', 'public.devices', 'parent_id', 'UPDATE'), 'authenticated cannot update devices.parent_id');
select ok(not has_column_privilege('authenticated', 'public.devices', 'device_state', 'UPDATE'), 'authenticated cannot update devices.device_state');
select ok(not has_table_privilege('authenticated', 'public.children', 'UPDATE'), 'authenticated lacks broad children UPDATE');
select ok(has_column_privilege('authenticated', 'public.children', 'first_name', 'UPDATE'), 'authenticated may update children.first_name');
select ok(not has_column_privilege('authenticated', 'public.children', 'parent_id', 'UPDATE'), 'authenticated cannot update children.parent_id');
select ok(not has_table_privilege('authenticated', 'public.usage_logs', 'UPDATE'), 'authenticated lacks broad usage_logs UPDATE');
select ok(has_column_privilege('authenticated', 'public.usage_logs', 'usage_minutes', 'UPDATE'), 'authenticated may update usage_minutes');
select ok(not has_column_privilege('authenticated', 'public.usage_logs', 'device_id', 'UPDATE'), 'authenticated cannot update usage_logs.device_id');
select ok((select bool_and(not has_table_privilege('authenticated', 'public.' || table_name, 'INSERT') and not has_table_privilege('authenticated', 'public.' || table_name, 'UPDATE') and not has_table_privilege('authenticated', 'public.' || table_name, 'DELETE')) from unnest(array['grants','device_push_tokens','device_heartbeats','outbox']) as table_name), 'service-backed surfaces deny direct authenticated writes');
select ok(not has_table_privilege('authenticated', 'public.grants', 'INSERT'), 'authenticated cannot insert grants');
select ok(not has_table_privilege('authenticated', 'public.outbox', 'INSERT'), 'authenticated cannot insert outbox rows');
select ok(not has_table_privilege('authenticated', 'public.device_heartbeats', 'INSERT'), 'authenticated cannot insert heartbeats');

select * from finish();
rollback;
