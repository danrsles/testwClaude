package com.example.demo.services;

import java.util.List;

import com.example.demo.models.Category;
import com.example.demo.models.User;
import com.example.demo.models.Want;
import com.example.demo.repositories.UserRepository;
import com.example.demo.repositories.WantRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WantService {

    private final WantRepository wantRepository;
    private final UserRepository userRepository;

    public WantService(WantRepository wantRepository, UserRepository userRepository) {
        this.wantRepository = wantRepository;
        this.userRepository = userRepository;
    }

    public List<Want> findAll() {
        return wantRepository.findAll();
    }

    public List<Want> findByCategory(Category category) {
        return wantRepository.findByCategory(category);
    }

    public Want create(String message, Category category, Long userId) {
        return wantRepository.save(new Want(message, category, requireUser(userId)));
    }

    /**
     * Full replacement: every field is overwritten, including the owner.
     * Transactional so the load and the write share one unit of work.
     */
    @Transactional
    public Want update(Long wantId, String message, Category category, Long userId) {
        Want want = wantRepository.findById(wantId)
                .orElseThrow(() -> new WantNotFoundException(wantId));

        want.setMessage(message);
        want.setCategory(category);
        want.setUser(requireUser(userId));

        return wantRepository.save(want);
    }

    @Transactional
    public void delete(Long wantId) {
        if (!wantRepository.existsById(wantId)) {
            throw new WantNotFoundException(wantId);
        }
        wantRepository.deleteById(wantId);
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
