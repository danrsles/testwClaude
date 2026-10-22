-- Closing and cancelling (see docs/design/wants-signups-and-notifications.md).
-- A want is ACTIVE while both are null, CLOSED while closed_at is set, and
-- CANCELLED once cancelled_at is set, which is final. Reopening clears
-- closed_at. Cancelling replaces deleting, so notifications keep pointing at
-- the want, and its lineup survives.
ALTER TABLE wants
    ADD COLUMN closed_at    TIMESTAMPTZ,
    ADD COLUMN cancelled_at TIMESTAMPTZ;

ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('FOLLOW', 'WANT_CREATED', 'WANT_SIGNUP', 'WANT_WITHDRAWN', 'WANT_UPDATED',
                    'WANT_CLOSED', 'WANT_REOPENED', 'WANT_CANCELLED'));
