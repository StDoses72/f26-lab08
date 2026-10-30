package edu.cmu.cs214.campusreserve.core.domain;

import java.time.Instant;
import java.util.Objects;

/** The old and new details of one booking modification, with its recording time. */
public record BookingModification(
    TimeInterval oldInterval,
    int oldAttendees,
    TimeInterval newInterval,
    int newAttendees,
    Instant modifiedAt) {

    public BookingModification {
        Objects.requireNonNull(oldInterval, "oldInterval");
        Objects.requireNonNull(newInterval, "newInterval");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        if (oldAttendees <= 0 || newAttendees <= 0) {
            throw new IllegalArgumentException("Attendee counts must be positive.");
        }
    }
}
