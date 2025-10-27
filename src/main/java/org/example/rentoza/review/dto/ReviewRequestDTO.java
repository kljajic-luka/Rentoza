package org.example.rentoza.review.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReviewRequestDTO {
    private Long carId;
    private int rating;
    private String comment;
}