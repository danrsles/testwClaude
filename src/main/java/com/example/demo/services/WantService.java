package com.example.demo.services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.models.Want;
import com.example.demo.repositories.WantRepository;
import com.example.demo.repositories.WantSignupRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each signed-in user sees their own wants and those of the people they
 * follow, but only a want's owner may change or delete it. The owner is
 * always the signed-in user, identified by the Clerk id from the token, never
 * an id sent in the request.
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

    public WantService(WantRepository wantRepository, WantSignupRepository wantSignupRepository,
            UserService userService, NotificationService notificationService) {
        this.wantRepository = wantRepository;
        this.wantSignupRepository = wantSignupRepository;
        this.userService = userService;
        this.notificationService = notificationService;
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
     * was created. Followers are not told, for now.
     */
    @Transactional
    public WantResponse create(String message, Category category, int capacity, ClerkIdentity who) {
        User me = userService.current(who);
        Want want = wantRepository.save(new Want(message, category, me, capacity));
        wantSignupRepository.signUpIfAbsent(want.getId(), me.getId());
        notificationService.notify(me.getId(), me, NotificationType.WANT_CREATED, want.getId());
        return withSeats(want, me);
    }

    /**
     * Replaces the message and category, and the capacity if one is given.
     * The owner never changes. Capacity can be raised but not lowered (400),
     * so nobody loses a seat they hold. A new name or category tells the
     * lineup, with the old name when it changed; a capacity raise alone, or
     * saving with nothing changed, tells nobody.
     */
    @Transactional
    public WantResponse update(Long wantId, String message, Category category, Integer capacity,
            ClerkIdentity who) {
        Want want = requireOwned(wantId, who.clerkId());
        if (capacity != null && capacity < want.getCapacity()) {
            throw new CapacityLoweredException(wantId, want.getCapacity(), capacity);
        }
        String previousMessage = want.getMessage();
        boolean renamed = !Objects.equals(previousMessage, message);
        boolean recategorised = want.getCategory() != category;

        want.setMessage(message);
        want.setCategory(category);
        if (capacity != null) {
            want.setCapacity(capacity);
        }
        // Flushed now, because notifyLineup copies the new message from the
        // table in SQL, not from this entity.
        Want saved = wantRepository.saveAndFlush(want);

        if (renamed || recategorised) {
            notificationService.notifyLineup(wantId, saved.getUser(), NotificationType.WANT_UPDATED,
                    renamed ? previousMessage : null);
        }
        return withSeats(saved, saved.getUser());
    }

    @Transactional
    public void delete(Long wantId, ClerkIdentity who) {
        wantRepository.delete(requireOwned(wantId, who.clerkId()));
    }

    /** One want as `me` sees it. */
    private WantResponse withSeats(Want want, User me) {
        return WantResponse.from(want, (int) wantSignupRepository.countByWantId(want.getId()),
                wantSignupRepository.existsByWantIdAndUserId(want.getId(), me.getId()));
    }

    /** A list of wants as `me` sees them, in two queries however long the list. */
    private List<WantResponse> withSeats(List<Want> wants, User me) {
        if (wants.isEmpty()) {
            return List.of();
        }
        List<Long> ids = wants.stream().map(Want::getId).toList();
        Map<Long, Integer> seats = new HashMap<>();
        for (Object[] row : wantSignupRepository.countSeats(ids)) {
            seats.put((Long) row[0], ((Long) row[1]).intValue());
        }
        Set<Long> mine = wantSignupRepository.findSignedUpWantIds(me.getId(), ids);
        return wants.stream()
                .map(want -> WantResponse.from(want, seats.getOrDefault(want.getId(), 0), mine.contains(want.getId())))
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
