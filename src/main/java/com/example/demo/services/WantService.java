package com.example.demo.services;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.demo.dto.UserSummary;
import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.models.Want;
import com.example.demo.models.WantStatus;
import com.example.demo.repositories.WantRepository;
import com.example.demo.repositories.WantSignupRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each signed-in user sees their own wants and those of the people they
 * follow, but only a want's owner may change, close, reopen, start, end or
 * cancel it. The owner is always the signed-in user, identified by the Clerk
 * id from the token, never an id sent in the request.
 *
 * Wants come back as WantResponse rather than the entity, because how full a
 * want is and whether you hold a seat are not on the entity: they are counted
 * from want_signups here.
 */
@Service
public class WantService {

    private final WantRepository wantRepository;
    private final WantSignupRepository wantSignupRepository;
    private final UserService userService;
    private final NotificationService notificationService;
    private final Clock clock;

    public WantService(WantRepository wantRepository, WantSignupRepository wantSignupRepository,
            UserService userService, NotificationService notificationService, Clock clock) {
        this.wantRepository = wantRepository;
        this.wantSignupRepository = wantSignupRepository;
        this.userService = userService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /**
     * The signed-in user's feed: their own wants and those of everyone they
     * follow. The signed-in user is brought up to date from their token
     * first, in the same transaction, so a picture or username just changed in
     * Clerk shows beside their own wants in this very response. Otherwise it
     * would depend on GET /me, which the page sends at the same time, happening
     * to finish first.
     */
    @Transactional
    public List<WantResponse> findAll(ClerkIdentity who) {
        User me = userService.current(who);
        return withSeats(wantRepository.findFeed(me.getId()), me);
    }

    /** As findAll, narrowed to one category. */
    @Transactional
    public List<WantResponse> findByCategory(Category category, ClerkIdentity who) {
        User me = userService.current(who);
        return withSeats(wantRepository.findFeedByCategory(me.getId(), category), me);
    }

    /**
     * Creates a want with its owner in the first seat, and tells the owner it
     * was created. Followers are not told, for now. expiresAt is its
     * deadline, or null for none; the controller has already refused one in
     * the past.
     */
    @Transactional
    public WantResponse create(String message, Category category, Integer capacity, Instant expiresAt,
            ClerkIdentity who) {
        User me = userService.current(who);
        Want created = new Want(message, category, me, capacity);
        created.setExpiresAt(expiresAt);
        Want want = wantRepository.save(created);
        wantSignupRepository.signUpIfAbsent(want.getId(), me.getId());
        notificationService.notify(me.getId(), me, NotificationType.WANT_CREATED, want.getId());
        return withSeats(want, me);
    }

    /**
     * Replaces the message and category, and the capacity if one is given.
     * The owner never changes. Only an active want can be edited (409): a
     * closed one is reopened first. Capacity can be raised but not lowered
     * (400), so nobody loses a seat they hold. openInvite true removes the
     * limit; false gives an open want back a limit of exactly the people
     * already in, counted under the row lock so no signup slips past it. An
     * open want takes no capacity (400), as any number is a lower limit. A
     * new name or category tells the lineup, with the old name when it
     * changed, and so do opening and limiting; a capacity raise alone, a
     * repeat that changes nothing, or saving unchanged tells nobody.
     *
     * expiresAt sets or moves the deadline and noDeadline true removes it;
     * with neither it stays as it was. A want past its deadline is closed,
     * so it cannot be edited. A deadline that changed tells the lineup
     * (WANT_DEADLINE_CHANGED); the same one again tells nobody.
     */
    @Transactional
    public WantResponse update(Long wantId, String message, Category category, Integer capacity,
            Boolean openInvite, Instant expiresAt, Boolean noDeadline, ClerkIdentity who) {
        Want want = requireOwned(wantId, who.clerkId());
        Instant now = clock.instant();
        requireNotFinal(want, now);
        if (want.getStatus(now) == WantStatus.CLOSED) {
            throw new WantClosedException(wantId);
        }
        Integer limit = want.getCapacity();
        if (capacity != null && (limit == null || capacity < limit)) {
            throw new CapacityLoweredException(wantId, limit, capacity);
        }
        String previousMessage = want.getMessage();
        boolean renamed = !Objects.equals(previousMessage, message);
        boolean recategorised = want.getCategory() != category;

        boolean opened = Boolean.TRUE.equals(openInvite) && limit != null;
        boolean limited = Boolean.FALSE.equals(openInvite) && limit == null;

        Instant deadline = Boolean.TRUE.equals(noDeadline) ? null
                : expiresAt != null ? expiresAt : want.getExpiresAt();
        boolean deadlineChanged = !Objects.equals(want.getExpiresAt(), deadline);

        want.setMessage(message);
        want.setCategory(category);
        if (opened) {
            want.setCapacity(null);
        } else if (limited) {
            want.setCapacity(Math.toIntExact(wantSignupRepository.countByWantId(wantId)));
        } else if (capacity != null) {
            want.setCapacity(capacity);
        }
        want.setExpiresAt(deadline);
        // Flushed now, because notifyLineup copies the new message from the
        // table in SQL, not from this entity.
        Want saved = wantRepository.saveAndFlush(want);

        if (renamed || recategorised) {
            notificationService.notifyLineup(wantId, saved.getUser(), NotificationType.WANT_UPDATED,
                    renamed ? previousMessage : null);
        }
        if (opened) {
            notificationService.notifyLineup(wantId, saved.getUser(), NotificationType.WANT_OPEN_INVITE, null);
        } else if (limited) {
            notificationService.notifyLineup(wantId, saved.getUser(), NotificationType.WANT_LIMITED, null);
        }
        if (deadlineChanged) {
            notificationService.notifyLineup(wantId, saved.getUser(), NotificationType.WANT_DEADLINE_CHANGED, null);
        }
        return withSeats(saved, saved.getUser());
    }

    /**
     * Fixes the lineup: no more signups or withdrawals, and no edits until it
     * is reopened. Tells the lineup, not the owner. Closing a closed want
     * changes nothing and tells nobody; an ended or cancelled one is a 409.
     */
    @Transactional
    public WantResponse close(Long wantId, ClerkIdentity who) {
        Want want = requireOwned(wantId, who.clerkId());
        Instant now = clock.instant();
        requireNotFinal(want, now);
        if (want.getStatus(now) == WantStatus.ACTIVE) {
            want.close(now);
            notificationService.notifyLineup(wantId, want.getUser(), NotificationType.WANT_CLOSED, null);
        }
        return withSeats(want, want.getUser());
    }

    /**
     * Opens a closed want to signups and edits again, and tells the lineup.
     * That includes one closed by its deadline. expiresAt, already checked to
     * be in the future, replaces the deadline; without one, a deadline still
     * ahead is kept and one that has passed is cleared. Reopening an active want
     * changes nothing and tells nobody, deadline included: an active want's
     * deadline is changed by update. An ended or cancelled one is a 409.
     */
    @Transactional
    public WantResponse reopen(Long wantId, Instant expiresAt, ClerkIdentity who) {
        Want want = requireOwned(wantId, who.clerkId());
        Instant now = clock.instant();
        requireNotFinal(want, now);
        if (want.getStatus(now) == WantStatus.CLOSED) {
            want.reopen(expiresAt, now);
            notificationService.notifyLineup(wantId, want.getUser(), NotificationType.WANT_REOPENED, null);
        }
        return withSeats(want, want.getUser());
    }

    /**
     * Calls the want off, active or closed, and tells the lineup. This is
     * what DELETE does: the row stays, with its lineup, so the notifications
     * that point at it still have something to show. It leaves the feed.
     * Cancelling twice tells nobody twice. An ended want has already
     * happened, so it cannot be called off (409).
     */
    @Transactional
    public void cancel(Long wantId, ClerkIdentity who) {
        Want want = requireOwned(wantId, who.clerkId());
        Instant now = clock.instant();
        // A switch expression, so a new status fails to compile until it is given a case here.
        boolean cancels = switch (want.getStatus(now)) {
            case ACTIVE, CLOSED -> true;
            case CANCELLED -> false;
            case ENDED -> throw new WantEndedException(wantId);
        };
        if (cancels) {
            want.cancel(now);
            notificationService.notifyLineup(wantId, want.getUser(), NotificationType.WANT_CANCELLED, null);
        }
    }

    /**
     * Marks the want as happening now, active or closed, and tells the
     * lineup. Who can join does not change: an active want still takes
     * signups, a closed one still does not. Starting a happening want changes
     * nothing and tells nobody; an ended or cancelled one is a 409.
     */
    @Transactional
    public WantResponse start(Long wantId, ClerkIdentity who) {
        Want want = requireOwned(wantId, who.clerkId());
        Instant now = clock.instant();
        requireNotFinal(want, now);
        if (!want.isHappening()) {
            want.start(now);
            notificationService.notifyLineup(wantId, want.getUser(), NotificationType.WANT_STARTED, null);
        }
        return withSeats(want, want.getUser());
    }

    /**
     * Ends a happening want, which is final, and tells the lineup. It stays
     * in the feed as ENDED. Ending an ended want changes nothing and tells
     * nobody; one that never started is a 409, as is a cancelled one.
     */
    @Transactional
    public WantResponse end(Long wantId, ClerkIdentity who) {
        Want want = requireOwned(wantId, who.clerkId());
        Instant now = clock.instant();
        if (want.getStatus(now) == WantStatus.ENDED) {
            return withSeats(want, want.getUser());
        }
        requireNotFinal(want, now);
        if (!want.isHappening()) {
            throw new WantNotHappeningException(wantId);
        }
        want.end(now);
        notificationService.notifyLineup(wantId, want.getUser(), NotificationType.WANT_ENDED, null);
        return withSeats(want, want.getUser());
    }

    /** Cancelling and ending are final: nothing else may happen to the want afterwards. */
    private static void requireNotFinal(Want want, Instant now) {
        if (want.getStatus(now) == WantStatus.CANCELLED) {
            throw new WantCancelledException(want.getId());
        }
        if (want.getStatus(now) == WantStatus.ENDED) {
            throw new WantEndedException(want.getId());
        }
    }

    /**
     * Writes closed_at on every want whose deadline has passed and tells
     * each one's lineup and owner. Run every minute by WantExpiryJob.
     * Returns how many notifications it sent.
     *
     * Not needed for correctness: getStatus already treats such a want as
     * closed. It takes no row lock first, because the single UPDATE flips
     * each want at most once, even with two runs at the same time, and waits
     * behind any request that holds the lock (see WantRepository.expireDue).
     */
    @Transactional
    public int expireDue() {
        return wantRepository.expireDue();
    }

    /** One want as `me` sees it. */
    private WantResponse withSeats(Want want, User me) {
        return withSeats(List.of(want), me).getFirst();
    }

    /** A list of wants as `me` sees them, in one query for the lineups however long the list. */
    private List<WantResponse> withSeats(List<Want> wants, User me) {
        if (wants.isEmpty()) {
            return List.of();
        }
        List<Long> ids = wants.stream().map(Want::getId).toList();
        Map<Long, List<UserSummary>> lineups = new HashMap<>();
        for (Object[] row : wantSignupRepository.findLineups(ids)) {
            lineups.computeIfAbsent((Long) row[0], id -> new ArrayList<>())
                    .add(new UserSummary((Long) row[1], (String) row[2], (String) row[3]));
        }
        Instant now = clock.instant();
        return wants.stream()
                .map(want -> {
                    List<UserSummary> lineup = lineups.getOrDefault(want.getId(), List.of());
                    boolean signedUp = lineup.stream().anyMatch(person -> person.id().equals(me.getId()));
                    return WantResponse.from(want, lineup, signedUp, now);
                })
                .toList();
    }

    /**
     * Locks the want's row, then checks ownership: 404 if the want does not
     * exist, 403 if it belongs to someone else. Locked like a signup, so an
     * edit and a signup on the same want take turns.
     */
    private Want requireOwned(Long wantId, String clerkId) {
        Want want = wantRepository.findByIdForUpdate(wantId)
                .orElseThrow(() -> new WantNotFoundException(wantId));
        if (!clerkId.equals(want.getUser().getClerkId())) {
            throw new WantForbiddenException(wantId);
        }
        return want;
    }
}
