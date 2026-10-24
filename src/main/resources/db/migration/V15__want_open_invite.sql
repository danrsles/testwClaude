-- Open invite (see docs/design/wants-signups-and-notifications.md): a want
-- with no seat limit has a null capacity. wants_capacity_check (V12) already
-- passes null, since a CHECK fails only on false, so it stays as it is, and
-- so does the default of 1. Existing wants keep their capacity.
ALTER TABLE wants ALTER COLUMN capacity DROP NOT NULL;

-- Opening a want tells its lineup they can bring others; limiting it again
-- tells them the lineup is fixed at the people already in.
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('FOLLOW', 'WANT_CREATED', 'WANT_SIGNUP', 'WANT_WITHDRAWN', 'WANT_UPDATED',
                    'WANT_CLOSED', 'WANT_REOPENED', 'WANT_CANCELLED', 'WANT_STARTED', 'WANT_ENDED',
                    'WANT_OPEN_INVITE', 'WANT_LIMITED'));
