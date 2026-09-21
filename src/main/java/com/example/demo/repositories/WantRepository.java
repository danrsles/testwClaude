package com.example.demo.repositories;

import java.util.List;

import com.example.demo.models.Category;
import com.example.demo.models.Want;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WantRepository extends JpaRepository<Want, Long> {

    List<Want> findByCategory(Category category);
}
