package vn.ptit.iot.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = {SensorController.class, DeviceController.class})
public class FilterErrorHandler {
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidFilter() {
        return Map.of("message", "Invalid input. Please check your entries and try again.");
    }
}
