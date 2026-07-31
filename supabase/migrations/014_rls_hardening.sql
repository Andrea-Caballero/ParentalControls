-- BLK-03 Slice 1: least-privilege table RLS and grants.
-- SECURITY DEFINER RPC hardening is intentionally deferred to Slice 2.

-- Replace every tenant-table policy so permissive policies cannot stack.
DROP POLICY IF EXISTS devices_agent_select ON devices;
DROP POLICY IF EXISTS devices_agent_update ON devices;
DROP POLICY IF EXISTS devices_parent_select ON devices;
DROP POLICY IF EXISTS devices_parent_delete ON devices;
DROP POLICY IF EXISTS devices_insert ON devices;
DROP POLICY IF EXISTS devices_parent_update_child_assignment ON devices;
DROP POLICY IF EXISTS app_policies_agent_all ON app_policies;
DROP POLICY IF EXISTS app_policies_parent_select ON app_policies;
DROP POLICY IF EXISTS app_policies_parent_insert ON app_policies;
DROP POLICY IF EXISTS app_policies_parent_update ON app_policies;
DROP POLICY IF EXISTS grants_agent_select ON grants;
DROP POLICY IF EXISTS grants_parent_select ON grants;
DROP POLICY IF EXISTS grants_parent_insert ON grants;
DROP POLICY IF EXISTS grants_agent_update ON grants;
DROP POLICY IF EXISTS time_requests_agent_all ON time_requests;
DROP POLICY IF EXISTS time_requests_parent_select ON time_requests;
DROP POLICY IF EXISTS time_requests_parent_update ON time_requests;
DROP POLICY IF EXISTS usage_logs_agent_all ON usage_logs;
DROP POLICY IF EXISTS usage_logs_parent_select ON usage_logs;
DROP POLICY IF EXISTS device_push_tokens_agent_all ON device_push_tokens;
DROP POLICY IF EXISTS device_push_tokens_parent_select ON device_push_tokens;
DROP POLICY IF EXISTS device_push_tokens_parent_insert ON device_push_tokens;
DROP POLICY IF EXISTS device_push_tokens_parent_update ON device_push_tokens;
DROP POLICY IF EXISTS device_heartbeats_agent_all ON device_heartbeats;
DROP POLICY IF EXISTS device_heartbeats_parent_select ON device_heartbeats;
DROP POLICY IF EXISTS pairing_codes_owner_all ON pairing_codes;
DROP POLICY IF EXISTS schedules_agent_select ON schedules;
DROP POLICY IF EXISTS schedules_parent_all ON schedules;
DROP POLICY IF EXISTS outbox_agent_all ON outbox;
DROP POLICY IF EXISTS children_parent_select ON children;
DROP POLICY IF EXISTS children_parent_insert ON children;
DROP POLICY IF EXISTS children_parent_update ON children;
DROP POLICY IF EXISTS children_parent_delete ON children;
DROP POLICY IF EXISTS behavioral_events_parent_select ON behavioral_events;
DROP POLICY IF EXISTS behavioral_events_agent_insert ON behavioral_events;
DROP POLICY IF EXISTS behavioral_events_agent_select ON behavioral_events;
DROP POLICY IF EXISTS device_alerts_agent_insert ON device_alerts;
DROP POLICY IF EXISTS device_alerts_agent_select ON device_alerts;
DROP POLICY IF EXISTS device_alerts_parent_select ON device_alerts;
DROP POLICY IF EXISTS integrity_reports_agent_insert ON integrity_reports;
DROP POLICY IF EXISTS integrity_reports_agent_select ON integrity_reports;
DROP POLICY IF EXISTS integrity_reports_parent_select ON integrity_reports;
DROP POLICY IF EXISTS parent_own_checkins ON parent_outcome_checkins;
DROP POLICY IF EXISTS policy_templates_read ON policy_templates;

REVOKE ALL PRIVILEGES ON TABLE
  devices, children, pairing_codes, app_policies, grants, time_requests,
  usage_logs, device_push_tokens, device_heartbeats, schedules, outbox,
  behavioral_events, device_alerts, integrity_reports, parent_outcome_checkins
FROM PUBLIC, anon, authenticated;
GRANT ALL PRIVILEGES ON TABLE
  devices, children, pairing_codes, app_policies, grants, time_requests,
  usage_logs, device_push_tokens, device_heartbeats, schedules, outbox,
  behavioral_events, device_alerts, integrity_reports, parent_outcome_checkins
TO service_role;

GRANT SELECT ON TABLE
  devices, children, pairing_codes, app_policies, grants, time_requests,
  usage_logs, device_heartbeats, schedules, behavioral_events, device_alerts,
  integrity_reports, parent_outcome_checkins
TO authenticated;
GRANT UPDATE (child_id) ON devices TO authenticated;
GRANT INSERT (parent_id, first_name) ON children TO authenticated;
GRANT UPDATE (first_name) ON children TO authenticated;
GRANT DELETE ON children TO authenticated;
GRANT INSERT (device_id, package_name, state, daily_limit_minutes, allowed_windows, category)
  ON app_policies TO authenticated;
GRANT UPDATE (package_name, state, daily_limit_minutes, allowed_windows, category)
  ON app_policies TO authenticated;
GRANT DELETE ON app_policies TO authenticated;
GRANT INSERT (device_id, child_user_id, package_name, minutes_requested, reason, created_at)
  ON time_requests TO authenticated;
GRANT INSERT (device_id, package_name, bucket_date, usage_minutes)
  ON usage_logs TO authenticated;
GRANT UPDATE (usage_minutes) ON usage_logs TO authenticated;
GRANT INSERT (device_id, name, days, from_time, to_time, action, allow_list, is_active)
  ON schedules TO authenticated;
GRANT UPDATE (name, days, from_time, to_time, action, allow_list, is_active)
  ON schedules TO authenticated;
GRANT DELETE ON schedules TO authenticated;
GRANT INSERT (event_type, event_version, device_id, parent_id, client_ts, props, synced)
  ON behavioral_events TO authenticated;
GRANT INSERT (device_id, alert_type, severity, payload, dedup_key)
  ON device_alerts TO authenticated;
GRANT INSERT (device_id, report_hash, signature_valid, agent_version, platform, raw_payload, reported_at)
  ON integrity_reports TO authenticated;
GRANT INSERT (parent_id, device_id, rating, comment, period_start, period_end)
  ON parent_outcome_checkins TO authenticated;
GRANT UPDATE (device_id, rating, comment, period_start, period_end)
  ON parent_outcome_checkins TO authenticated;
GRANT DELETE ON parent_outcome_checkins TO authenticated;
REVOKE ALL PRIVILEGES ON SEQUENCE behavioral_events_id_seq FROM PUBLIC, anon, authenticated;
GRANT USAGE, SELECT ON SEQUENCE behavioral_events_id_seq TO authenticated;
GRANT ALL PRIVILEGES ON SEQUENCE behavioral_events_id_seq TO service_role;
GRANT SELECT ON policy_templates TO anon, authenticated, service_role;

-- Policy/schedule changes bump devices.policy_version through this trigger.
-- Definer execution preserves that internal write while clients retain only
-- UPDATE(child_id) on devices; this trigger function is not an RPC surface.
ALTER FUNCTION bump_policy_version_trigger() SECURITY DEFINER;
ALTER FUNCTION bump_policy_version_trigger() SET search_path = public, pg_temp;
REVOKE ALL ON FUNCTION bump_policy_version_trigger() FROM PUBLIC, anon, authenticated;

CREATE POLICY devices_device_select ON devices FOR SELECT TO authenticated
  USING ((SELECT auth.jwt() ->> 'device_id') = id::text);
CREATE POLICY devices_parent_select ON devices FOR SELECT TO authenticated
  USING (parent_id = (SELECT auth.uid()));
CREATE POLICY devices_parent_update_child ON devices FOR UPDATE TO authenticated
  USING (parent_id = (SELECT auth.uid()))
  WITH CHECK (
    parent_id = (SELECT auth.uid()) AND
    (child_id IS NULL OR EXISTS (
      SELECT 1 FROM children c
      WHERE c.id = devices.child_id AND c.parent_id = (SELECT auth.uid())
    ))
  );

CREATE POLICY children_parent_select ON children FOR SELECT TO authenticated
  USING (parent_id = (SELECT auth.uid()));
CREATE POLICY children_parent_insert ON children FOR INSERT TO authenticated
  WITH CHECK (parent_id = (SELECT auth.uid()));
CREATE POLICY children_parent_update ON children FOR UPDATE TO authenticated
  USING (parent_id = (SELECT auth.uid()))
  WITH CHECK (parent_id = (SELECT auth.uid()));
CREATE POLICY children_parent_delete ON children FOR DELETE TO authenticated
  USING (parent_id = (SELECT auth.uid()));
CREATE POLICY pairing_codes_parent_select ON pairing_codes FOR SELECT TO authenticated
  USING (parent_id = (SELECT auth.uid()));

CREATE POLICY app_policies_device_select ON app_policies FOR SELECT TO authenticated
  USING ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY app_policies_parent_select ON app_policies FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = app_policies.device_id AND d.parent_id = (SELECT auth.uid())));
CREATE POLICY app_policies_parent_insert ON app_policies FOR INSERT TO authenticated
  WITH CHECK (EXISTS (SELECT 1 FROM devices d WHERE d.id = app_policies.device_id AND d.parent_id = (SELECT auth.uid())));
CREATE POLICY app_policies_parent_update ON app_policies FOR UPDATE TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = app_policies.device_id AND d.parent_id = (SELECT auth.uid())))
  WITH CHECK (EXISTS (SELECT 1 FROM devices d WHERE d.id = app_policies.device_id AND d.parent_id = (SELECT auth.uid())));
CREATE POLICY app_policies_parent_delete ON app_policies FOR DELETE TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = app_policies.device_id AND d.parent_id = (SELECT auth.uid())));

CREATE POLICY grants_device_select ON grants FOR SELECT TO authenticated
  USING ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY grants_parent_select ON grants FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = grants.device_id AND d.parent_id = (SELECT auth.uid())));
CREATE POLICY time_requests_device_select ON time_requests FOR SELECT TO authenticated
  USING ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY time_requests_device_insert ON time_requests FOR INSERT TO authenticated
  WITH CHECK ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY time_requests_parent_select ON time_requests FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = time_requests.device_id AND d.parent_id = (SELECT auth.uid())));

CREATE POLICY usage_logs_device_select ON usage_logs FOR SELECT TO authenticated
  USING ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY usage_logs_device_insert ON usage_logs FOR INSERT TO authenticated
  WITH CHECK ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY usage_logs_device_update ON usage_logs FOR UPDATE TO authenticated
  USING ((SELECT auth.jwt() ->> 'device_id') = device_id::text)
  WITH CHECK ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY usage_logs_parent_select ON usage_logs FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = usage_logs.device_id AND d.parent_id = (SELECT auth.uid())));

CREATE POLICY device_heartbeats_device_select ON device_heartbeats FOR SELECT TO authenticated
  USING ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY device_heartbeats_parent_select ON device_heartbeats FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = device_heartbeats.device_id AND d.parent_id = (SELECT auth.uid())));
CREATE POLICY schedules_device_select ON schedules FOR SELECT TO authenticated
  USING ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY schedules_parent_select ON schedules FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = schedules.device_id AND d.parent_id = (SELECT auth.uid())));
CREATE POLICY schedules_parent_insert ON schedules FOR INSERT TO authenticated
  WITH CHECK (EXISTS (SELECT 1 FROM devices d WHERE d.id = schedules.device_id AND d.parent_id = (SELECT auth.uid())));
CREATE POLICY schedules_parent_update ON schedules FOR UPDATE TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = schedules.device_id AND d.parent_id = (SELECT auth.uid())))
  WITH CHECK (EXISTS (SELECT 1 FROM devices d WHERE d.id = schedules.device_id AND d.parent_id = (SELECT auth.uid())));
CREATE POLICY schedules_parent_delete ON schedules FOR DELETE TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = schedules.device_id AND d.parent_id = (SELECT auth.uid())));

CREATE POLICY behavioral_events_device_insert ON behavioral_events FOR INSERT TO authenticated
  WITH CHECK (
    (SELECT auth.jwt() ->> 'device_id') = device_id::text AND
    (parent_id IS NULL OR EXISTS (
      SELECT 1 FROM devices d
      WHERE d.id = behavioral_events.device_id AND d.parent_id = behavioral_events.parent_id
    ))
  );
CREATE POLICY behavioral_events_device_select ON behavioral_events FOR SELECT TO authenticated
  USING ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY behavioral_events_parent_select ON behavioral_events FOR SELECT TO authenticated
  USING (
    (device_id IS NULL AND parent_id = (SELECT auth.uid())) OR
    EXISTS (SELECT 1 FROM devices d WHERE d.id = behavioral_events.device_id AND d.parent_id = (SELECT auth.uid()))
  );
CREATE POLICY device_alerts_device_insert ON device_alerts FOR INSERT TO authenticated
  WITH CHECK ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY device_alerts_device_select ON device_alerts FOR SELECT TO authenticated
  USING ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY device_alerts_parent_select ON device_alerts FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = device_alerts.device_id AND d.parent_id = (SELECT auth.uid())));
CREATE POLICY integrity_reports_device_insert ON integrity_reports FOR INSERT TO authenticated
  WITH CHECK ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY integrity_reports_device_select ON integrity_reports FOR SELECT TO authenticated
  USING ((SELECT auth.jwt() ->> 'device_id') = device_id::text);
CREATE POLICY integrity_reports_parent_select ON integrity_reports FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM devices d WHERE d.id = integrity_reports.device_id AND d.parent_id = (SELECT auth.uid())));

CREATE POLICY parent_checkins_parent_select ON parent_outcome_checkins FOR SELECT TO authenticated
  USING (parent_id = (SELECT auth.uid()) AND (device_id IS NULL OR EXISTS (
    SELECT 1 FROM devices d WHERE d.id = parent_outcome_checkins.device_id AND d.parent_id = (SELECT auth.uid())
  )));
CREATE POLICY parent_checkins_parent_insert ON parent_outcome_checkins FOR INSERT TO authenticated
  WITH CHECK (parent_id = (SELECT auth.uid()) AND (device_id IS NULL OR EXISTS (
    SELECT 1 FROM devices d WHERE d.id = parent_outcome_checkins.device_id AND d.parent_id = (SELECT auth.uid())
  )));
CREATE POLICY parent_checkins_parent_update ON parent_outcome_checkins FOR UPDATE TO authenticated
  USING (parent_id = (SELECT auth.uid()))
  WITH CHECK (parent_id = (SELECT auth.uid()) AND (device_id IS NULL OR EXISTS (
    SELECT 1 FROM devices d WHERE d.id = parent_outcome_checkins.device_id AND d.parent_id = (SELECT auth.uid())
  )));
CREATE POLICY parent_checkins_parent_delete ON parent_outcome_checkins FOR DELETE TO authenticated
  USING (parent_id = (SELECT auth.uid()));
CREATE POLICY policy_templates_read ON policy_templates FOR SELECT TO anon, authenticated
  USING (true);
