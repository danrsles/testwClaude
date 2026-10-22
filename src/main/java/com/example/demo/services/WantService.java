package com.example.demo.services;

import java.util.List;

import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;
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
        return withSeats(wantRepository.findFeed(userService.current(who).getId()));
    }

    /** As findAll, narrowed to one category. */
    @Transactional
    public List<WantResponse> findByCategory(Category category, ClerkIdentity who) {
        return withSeats(wantRepository.findFeedByCategory(userService.current(who).getId(), category));
    }

    @Transactional
    public WantResponse create(String message, Category category, int capacity, ClerkIdentity who) {
        Want want = wantRepository.save(new Want(message, category, userService.current(who), capacity));
        return WantResponse.from(want, 0, false);
    }

    /** Replaces the message and category. The owner never changes. */
    @Transactional
    public WantResponse update(Long wantId, String message, Category category, Integer capacity,
            ClerkIdentity who) {
        Want want = requireOwned(wantId, who.clerkId());

        want.setMessage(message);
        want.setCategory(category);

        return WantResponse.from(wantRepository.save(want), 0, false);
    }

    @Transactional
    public void delete(Long wantId, ClerkIdentity who) {
        wantRepository.delete(requireOwned(wantId, who.clerkId()));
    }

    /** Not built yet: every want reads as empty. */
    private List<WantResponse> withSeats(List<Want> wants) {
        return wants.stream().map(want -> WantResponse.from(want, 0, false)).toList();
    }

    /** 404 if the want does not exist, 403 if it belongs to someone else. */
    private Want requireOwned(Long wantId, String clerkId) {
        Want want = wantRepository.findById(wantId)
                .orElseThrow(() -> new WantNotFoundException(wantId));
        if (!clerkId.equals(want.getUser().getClerkId())) {
            throw new WantForbiddenException(wantId);
        }
        return want;
    }
}
