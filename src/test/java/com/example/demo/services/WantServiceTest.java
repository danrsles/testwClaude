package com.example.demo.services;

import java.util.List;
import java.util.Optional;

import com.example.demo.models.Category;
import com.example.demo.models.User;
import com.example.demo.models.Want;
import com.example.demo.repositories.WantRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

/** The ownership rule, which the controller tests mock away. */
@ExtendWith(MockitoExtension.class)
class WantServiceTest {

    @Mock
    private WantRepository wantRepository;

    @Mock
    private UserService userService;

    @InjectMocks
    private WantService wantService;

    private static final ClerkIdentity DANI = new ClerkIdentity("user_dani", "dani");
    private static final ClerkIdentity MALLORY = new ClerkIdentity("user_mallory", "mallory");

    private final User dani = new User("dani", "user_dani");
    // The seeded user from before logins: no Clerk account can match it.
    private final User legacy = new User("legacy");

    @Test
    void createsWantsForTheSignedInUser() {
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.save(any(Want.class))).willAnswer(call -> call.getArgument(0));

        Want saved = wantService.create("Tacos", Category.FOOD, DANI);

        assertThat(saved.getUser()).isSameAs(dani);
    }

    @Test
    void ownerMayUpdateTheirWant() {
        Want want = new Want("Ramen", Category.FOOD, dani);
        given(wantRepository.findById(1L)).willReturn(Optional.of(want));
        given(wantRepository.save(want)).willReturn(want);

        Want updated = wantService.update(1L, "Pho", Category.FOOD, DANI);

        assertThat(updated.getMessage()).isEqualTo("Pho");
        assertThat(updated.getUser()).isSameAs(dani);
    }

    @Test
    void othersMayNotUpdateIt() {
        given(wantRepository.findById(1L)).willReturn(Optional.of(new Want("Ramen", Category.FOOD, dani)));

        assertThatThrownBy(() -> wantService.update(1L, "Mine now", Category.FOOD, MALLORY))
                .isInstanceOf(WantForbiddenException.class);
        then(wantRepository).should(never()).save(any());
    }

    @Test
    void othersMayNotDeleteIt() {
        given(wantRepository.findById(1L)).willReturn(Optional.of(new Want("Ramen", Category.FOOD, dani)));

        assertThatThrownBy(() -> wantService.delete(1L, MALLORY))
                .isInstanceOf(WantForbiddenException.class);
        then(wantRepository).should(never()).delete(any());
    }

    @Test
    void wantsWithoutAClerkOwnerAreReadOnly() {
        given(wantRepository.findById(1L)).willReturn(Optional.of(new Want("Ramen", Category.FOOD, legacy)));

        assertThatThrownBy(() -> wantService.delete(1L, DANI))
                .isInstanceOf(WantForbiddenException.class);
    }

    @Test
    void unknownWantIsNotFound() {
        given(wantRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> wantService.delete(99L, DANI))
                .isInstanceOf(WantNotFoundException.class);
    }

    @Test
    void bringsTheSignedInUserUpToDateBeforeListing() {
        // So a picture just changed in Clerk is in this response, not only the next one.
        given(wantRepository.findAll()).willReturn(List.of());

        wantService.findAll(DANI);

        InOrder order = inOrder(userService, wantRepository);
        order.verify(userService).current(DANI);
        order.verify(wantRepository).findAll();
    }

    @Test
    void bringsTheSignedInUserUpToDateBeforeFiltering() {
        given(wantRepository.findByCategory(Category.FOOD)).willReturn(List.of());

        wantService.findByCategory(Category.FOOD, DANI);

        InOrder order = inOrder(userService, wantRepository);
        order.verify(userService).current(DANI);
        order.verify(wantRepository).findByCategory(Category.FOOD);
    }
}
