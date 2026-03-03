-- V77: Enforce immutability of admin_audit_log at database level.
-- Prevents UPDATE and DELETE operations on audit trail entries,
-- ensuring tamper-proof audit records regardless of application bugs.

-- Trigger function that rejects modifications
CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit_log is immutable: % operations are not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

-- Block UPDATE on audit log rows
CREATE TRIGGER audit_log_immutable_update
    BEFORE UPDATE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

-- Block DELETE on audit log rows
CREATE TRIGGER audit_log_immutable_delete
    BEFORE DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();
