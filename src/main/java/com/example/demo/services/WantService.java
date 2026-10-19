package com.example.demo.services;

import java.util.List;

import com.example.demo.models.Category;
import com.example.demo.models.Want;
import com.example.demo.repositories.WantRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every signed-in user sees every want, but only its owner may change or
 * delete it. The owner is always the signed-in user, identified by the Clerk
 * id from the token, never an id sent in the request.
 */
@Service
public class WantService {

    private final WantRepository wantRepository;
    private final UserService userService;

    public WantService(WantRepository wantRepository, UserService userService) {
        this.wantRepository = wantRepository;
        this.userService = userService;
    }

    /**
     * Every want. The signed-in user is brought up to date from their token
     * first, in the same transaction, so a picture or username just changed in
     * Clerk shows beside their own wants in this very response. Otherwise it
     * would depend on GET /me, which the page sends at the same time, happening
     * to finish first.
     */
    @Transactional
    public List<Want> findAll(ClerkIdentity who) {
        userService.current(who);
        return wantRepository.findAll();
    }

    /** As findAll, narrowed to one category. */
    @Transactional
    public List<Want> findByCategory(Category category, ClerkIdentity who) {
        userService.current(who);
        return wantRepository.findByCategory(category);
    }

    @Transactional
    public Want create(String message, Category category, ClerkIdentity who) {
        return wantRepository.save(new Want(message, category, userService.current(who)));
    }

    /** Replaces the message and category. The owner never changes. */
    @Transactional
    public Want update(Long wantId, String message, Category category, ClerkIdentity who) {
        Want want = requireOwned(wantId, who.clerkId());

        want.setMessage(message);
        want.setCategory(category);

        return wantRepository.save(want);
    }

    @Transactional
    public void delete(Long wantId, ClerkIdentity who) {
        wantRepository.delete(requireOwned(wantId, who.clerkId()));
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
