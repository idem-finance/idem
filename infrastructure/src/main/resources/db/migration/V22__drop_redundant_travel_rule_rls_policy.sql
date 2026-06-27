-- tenant_read (FOR SELECT) is fully subsumed by tenant_isolation (FOR ALL), which was created
-- in V21. PostgreSQL OR's permissive policies per command; having two identical SELECT policies
-- means a future tightening of tenant_isolation would be silently bypassed by the remaining one.
DROP POLICY tenant_read ON travel_rule_data;
