package com.example.relationships.repository;

import java.util.Optional;

import com.example.relationships.model.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovieRepository extends JpaRepository<Movie, Long> {
    Optional<Movie> findByTitle(String title);
}
