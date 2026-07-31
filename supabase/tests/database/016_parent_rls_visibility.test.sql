begin;

create extension if not exists pgtap with schema extensions;
set search_path = public, extensions;
select plan(6);

-- Deterministic fixtures are inserted by the test owner before switching to the
-- non-bypass application role. The transaction rollback removes all fixtures.
insert into auth.users (id, email) values
  ('11000000-0000-0000-0000-000000000001', 'rls-parent-a@example.test'),
  ('11000000-0000-0000-0000-000000000002', 'rls-parent-b@example.test');
insert into children (id, parent_id, first_name) values
  ('31000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', 'RLS A'),
  ('31000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000002', 'RLS B');
insert into devices (id, device_name, parent_id, app_version, child_id) values
  ('41000000-0000-0000-0000-000000000001', 'rls-device-a', '11000000-0000-0000-0000-000000000001', '1', '31000000-0000-0000-0000-000000000001'),
  ('41000000-0000-0000-0000-000000000002', 'rls-device-b', '11000000-0000-0000-0000-000000000002', '1', '31000000-0000-0000-0000-000000000002');
insert into behavioral_events (id, event_type, device_id, parent_id, client_ts) values
  (9100001, 'rls-parent-device', '41000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', '2026-01-01T00:00:00Z'),
  (9100002, 'rls-device-only', '41000000-0000-0000-0000-000000000001', null, '2026-01-01T00:01:00Z'),
  (9100003, 'rls-parent-only', null, '11000000-0000-0000-0000-000000000001', '2026-01-01T00:02:00Z'),
  (9100004, 'rls-sibling-parent', null, '11000000-0000-0000-0000-000000000002', '2026-01-01T00:03:00Z');
insert into device_push_tokens (id, device_id, parent_id, token) values
  ('71000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', 'rls-token-a'),
  ('71000000-0000-0000-0000-000000000002', '41000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000002', 'rls-token-b');

select ok(
  not has_table_privilege('authenticated', 'public.device_push_tokens', 'SELECT'),
  'push tokens remain service-only and authenticated lacks direct SELECT');

select set_config('request.jwt.claims',
  '{"sub":"11000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
set local role authenticated;

select results_eq(
  $$select id from behavioral_events order by id$$,
  $$values (9100001::bigint), (9100002::bigint), (9100003::bigint)$$,
  'parent A sees all three behavioral events owned directly or through its device');
select is(
  (select count(*) from behavioral_events where parent_id = '11000000-0000-0000-0000-000000000002'),
  0::bigint,
  'parent A sees zero behavioral events belonging to sibling parent B');
select throws_ok(
  $$select token from device_push_tokens$$,
  '42501', null, 'parent A cannot read raw push tokens directly');

reset role;
select set_config('request.jwt.claims',
  '{"sub":"11000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
set local role authenticated;

select results_eq(
  $$select id from behavioral_events order by id$$,
  $$values (9100004::bigint)$$,
  'parent B sees only its own behavioral event');
select throws_ok(
  $$select token from device_push_tokens$$,
  '42501', null, 'parent B cannot read raw push tokens directly');

select * from finish();
rollback;
