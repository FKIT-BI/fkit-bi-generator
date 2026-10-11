package ru.fkit.bi.generator.run;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class RunNotFoundException extends ResponseStatusException {
  public RunNotFoundException() { super(HttpStatus.NOT_FOUND, "Run not found"); }
}
