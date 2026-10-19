package com.example.demo.repositories;

import java.util.List;

import com.example.demo.models.Category;
import com.example.demo.models.Want;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WantRepository extends JpaRepository<Want, Long> {

    /** A user's feed: their own wants and those of everyone they follow. */
    @Query("""
            select w from Want w
            where w.user.id = :userId
               or w.user.id in (select f.followee.id from Follow f where f.follower.id = :userId)
            order by w.id""")
    List<Want> findFeed(@Param("userId") Long userId);

    /** As findFeed, narrowed to one category. */
    @Query("""
            select w from Want w
            where w.category = :category
              and (w.user.id = :userId
                   or w.user.id in (select f.followee.id from Follow f where f.follower.id = :userId))
            order by w.id""")
    List<Want> findFeedByCategory(@Param("userId") Long userId, @Param("category") Category category);
}
