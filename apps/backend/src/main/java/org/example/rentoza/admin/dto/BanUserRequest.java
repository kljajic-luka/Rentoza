package org.example.rentoza.admin.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BanUserRequest {
    private String reason;
}
