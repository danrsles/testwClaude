package com.example.demo.services;

import java.util.List;

import com.example.demo.models.Notification;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.repositories.NotificationRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/** Whose letterbox is read and written. Always the signed-in user's, never an id from the request. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserService userService;

    @InjectMocks
    private NotificationService notificationService;

    private static final ClerkIdentity DANI = new ClerkIdentity("user_dani", "dani");

    private final User dani = withId(new User("dani", "user_dani"), 1L);
    private final User alex = withId(new User("alex"), 2L);

    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void notifiesTheRecipientFromTheActor() {
        notificationService.notify(2L, dani, NotificationType.FOLLOW);

        then(notificationRepository).should().notify(2L, 1L, "FOLLOW");
    }

    @Test
    void listsOnlyYourOwn() {
        Notification followed = new Notification(dani, alex, NotificationType.FOLLOW);
        given(userService.current(DANI)).willReturn(dani);
        given(notificationRepository.findTop50ByRecipientIdOrderByIdDesc(1L)).willReturn(List.of(followed));

        assertThat(notificationService.mine(DANI)).containsExactly(followed);
    }

    @Test
    void marksOnlyYourOwnRead() {
        given(userService.current(DANI)).willReturn(dani);

        notificationService.markRead(7L, DANI);

        then(notificationRepository).should().markRead(1L, 7L);
    }
}
