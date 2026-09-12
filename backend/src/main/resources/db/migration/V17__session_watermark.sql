-- ===========================================================================
-- V17  Session revocation and hashed reset tokens
--
-- sessions_valid_after is a per-user watermark. Access and refresh tokens are
-- stateless JWTs, so nothing server-side can delete one; instead, any token
-- issued before this instant is refused. Moving it forward is how a password
-- reset or a password change ends every session that existed before it.
-- Null means nothing has been revoked, so every existing session stays valid.
--
-- Reset tokens are stored as a SHA-256 digest from now on, so a copy of the
-- database cannot be used to reset anybody's password. A raw token already in
-- the table would never match a digest, so the outstanding ones are cleared
-- rather than left behind: anybody part-way through a reset asks for a new link.
-- ===========================================================================

ALTER TABLE users ADD COLUMN sessions_valid_after timestamp with time zone;

UPDATE users
SET password_reset_token = NULL,
    password_reset_expires_at = NULL
WHERE password_reset_token IS NOT NULL;
