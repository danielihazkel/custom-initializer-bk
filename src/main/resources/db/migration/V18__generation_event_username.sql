-- V18: Attribute generation audit events to a username (from the SSO `userinfo` header)

ALTER TABLE initializer_generation_event ADD COLUMN username VARCHAR(255);
