-- Starting and ending (see docs/design/wants-signups-and-notifications.md).
-- started_at says the want is happening: it is live. That is not a status,
-- since an active want can be happening and still take signups, and a closed
-- one can be happening with its lineup fixed. ended_at makes it ENDED, which
-- is final; unlike a cancelled want, an ended one stays in the feed.
ALTER TABLE wants
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN ended_at   TIMESTAMPTZ;

ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('FOLLOW', 'WANT_CREATED', 'WANT_SIGNUP', 'WANT_WITHDRAWN', 'WANT_UPDATED',
                    'WANT_CLOSED', 'WANT_REOPENED', 'WANT_CANCELLED', 'WANT_STARTED', 'WANT_ENDED'));
