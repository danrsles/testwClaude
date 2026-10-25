-- Deadlines (see docs/design/wants-signups-and-notifications.md, step 6). A
-- want past its expires_at counts as closed straight away; WantService.expireDue
-- later writes closed_at, when it actually ran, and tells the lineup and the
-- owner. Null means no deadline, which every existing want keeps.
ALTER TABLE wants ADD COLUMN expires_at TIMESTAMPTZ NULL;

-- A changed deadline tells the lineup; closing at the deadline tells the
-- lineup and the owner.
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('FOLLOW', 'WANT_CREATED', 'WANT_SIGNUP', 'WANT_WITHDRAWN', 'WANT_UPDATED',
                    'WANT_CLOSED', 'WANT_REOPENED', 'WANT_CANCELLED', 'WANT_STARTED', 'WANT_ENDED',
                    'WANT_OPEN_INVITE', 'WANT_LIMITED', 'WANT_DEADLINE_CHANGED', 'WANT_EXPIRED'));
