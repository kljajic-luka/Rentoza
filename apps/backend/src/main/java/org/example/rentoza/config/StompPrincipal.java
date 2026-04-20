package org.example.rentoza.config;

import java.security.Principal;

public record StompPrincipal(String value) implements Principal {

	@Override
	public String getName() {
		return value;
	}
}