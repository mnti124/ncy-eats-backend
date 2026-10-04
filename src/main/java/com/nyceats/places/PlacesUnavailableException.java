package com.nyceats.places;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class PlacesUnavailableException extends RuntimeException {
    public PlacesUnavailableException(String message) {
        super(message);
    }

    public PlacesUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
