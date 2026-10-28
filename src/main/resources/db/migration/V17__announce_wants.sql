-- Announcing new wants to followers (see docs/design/wants-signups-and-notifications.md,
-- step 8a). WantService tells the owner's followers about a want they can join,
-- after every create and edit, through NotificationRepository.announce.
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('FOLLOW', 'WANT_CREATED', 'WANT_SIGNUP', 'WANT_WITHDRAWN', 'WANT_UPDATED',
                    'WANT_CLOSED', 'WANT_REOPENED', 'WANT_CANCELLED', 'WANT_STARTED', 'WANT_ENDED',
                    'WANT_OPEN_INVITE', 'WANT_LIMITED', 'WANT_DEADLINE_CHANGED', 'WANT_EXPIRED',
                    'WANT_POSTED'));

-- The "already told" marker: one WANT_POSTED per want and recipient. announce
-- ends in ON CONFLICT DO NOTHING against it, so running it after every edit
-- tells only people who haven't heard yet. A job that ever deletes old
-- notifications must keep these rows, or people would be told again.
CREATE UNIQUE INDEX notifications_posted_once ON notifications (want_id, recipient_id)
    WHERE type = 'WANT_POSTED';

-- announce finds an owner's followers. UNIQUE (follower_id, followee_id) only
-- serves lookups by follower.
CREATE INDEX follows_followee_id ON follows (followee_id);
