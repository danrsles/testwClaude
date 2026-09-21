package com.example.demo.services;

import java.util.List;

import com.example.demo.models.Category;
import com.example.demo.models.User;
import com.example.demo.models.Want;
import com.example.demo.repositories.UserRepository;
import com.example.demo.repositories.WantRepository;

import org.springframework.stereotype.Service;

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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        return wantRepository.save(new Want(message, category, user));
    }
}
