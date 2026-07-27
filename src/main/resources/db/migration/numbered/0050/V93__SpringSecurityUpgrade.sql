-- Serialized session information is not backward-compatible with older Spring Security versions,
-- so we need to invalidate any existing sessions.
DELETE FROM spring_session_attributes;
DELETE FROM spring_session;
