package edu.cmu.cs214.campusreserve.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.campusreserve.core.support.Fixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingHistoryTest {

    private static final Instant CREATED = Instant.parse(Fixtures.NOW);
    private static final Instant FIRST_CHANGE = Instant.parse("2026-10-19T09:15:00Z");
    private static final Instant SECOND_CHANGE = Instant.parse("2026-10-19T09:30:00Z");
    private static final TimeInterval ORIGINAL = Fixtures.interval("10:00", "11:00");
    private static final TimeInterval MOVED = Fixtures.interval("10:15", "11:15");

    @Test
    void existingConstructionStartsWithEmptyHistory() {
        Booking constructed = new Booking(
            "bk-1", "r-1", "u-1", ORIGINAL, 4, BookingStatus.CONFIRMED, CREATED);

        assertTrue(constructed.history().isEmpty());
        assertEquals(constructed, booking());
    }

    @Test
    void recordsBothChangedDetailsAndTheSuppliedTimestamp() {
        Booking original = booking();
        Booking changed = original.modified(MOVED, 6, FIRST_CHANGE);

        assertEquals(MOVED, changed.interval());
        assertEquals(6, changed.attendees());
        assertEquals(List.of(new BookingModification(
            ORIGINAL, 4, MOVED, 6, FIRST_CHANGE)), changed.history());
        assertEquals(original.id(), changed.id());
        assertEquals(original.roomId(), changed.roomId());
        assertEquals(original.userId(), changed.userId());
        assertEquals(original.status(), changed.status());
        assertEquals(original.createdAt(), changed.createdAt());
        assertEquals(60, changed.durationMinutes());
    }

    @Test
    void recordsATimeOnlyChange() {
        TimeInterval shortened = Fixtures.interval("10:00", "10:30");
        Booking changed = booking().modified(shortened, 4, FIRST_CHANGE);

        assertEquals(shortened, changed.interval());
        assertEquals(30, changed.durationMinutes());
        assertEquals(List.of(new BookingModification(
            ORIGINAL, 4, shortened, 4, FIRST_CHANGE)), changed.history());
    }

    @Test
    void recordsAnAttendeeOnlyChange() {
        Booking changed = booking().modified(ORIGINAL, 6, FIRST_CHANGE);

        assertEquals(ORIGINAL, changed.interval());
        assertEquals(6, changed.attendees());
        assertEquals(List.of(new BookingModification(
            ORIGINAL, 4, ORIGINAL, 6, FIRST_CHANGE)), changed.history());
    }

    @Test
    void appendsInOrderWithoutChangingEarlierSnapshots() {
        Booking original = booking();
        Booking first = original.modified(MOVED, 6, FIRST_CHANGE);
        Booking second = first.modified(MOVED, 3, SECOND_CHANGE);

        assertEquals(List.of(
            new BookingModification(ORIGINAL, 4, MOVED, 6, FIRST_CHANGE),
            new BookingModification(MOVED, 6, MOVED, 3, SECOND_CHANGE)), second.history());
        assertEquals(3, second.attendees());
        assertEquals(1, first.history().size());
        assertEquals(6, first.attendees());
        assertTrue(original.history().isEmpty());
        assertEquals(ORIGINAL, original.interval());
        assertEquals(4, original.attendees());
    }

    @Test
    void identicalDetailsDoNotCreateHistory() {
        Booking original = booking();
        TimeInterval equalInterval = Fixtures.interval("10:00", "11:00");
        Booking unchanged = original.modified(equalInterval, 4, FIRST_CHANGE);

        assertEquals(original, unchanged);
        assertTrue(unchanged.history().isEmpty());
    }

    @Test
    void identicalDetailsPreserveExistingHistory() {
        Booking changed = booking().modified(MOVED, 6, FIRST_CHANGE);
        Booking unchanged = changed.modified(Fixtures.interval("10:15", "11:15"), 6, SECOND_CHANGE);

        assertEquals(changed, unchanged);
        assertEquals(1, unchanged.history().size());
        assertEquals(FIRST_CHANGE, unchanged.history().get(0).modifiedAt());
    }

    @Test
    void historyCannotBeModifiedThroughTheAccessor() {
        Booking changed = booking().modified(MOVED, 6, FIRST_CHANGE);

        assertThrows(UnsupportedOperationException.class, () -> changed.history().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> changed.history().add(changed.history().get(0)));
        assertThrows(UnsupportedOperationException.class,
            () -> booking().history().add(changed.history().get(0)));
    }

    @Test
    void constructorCopiesTheCallersHistoryList() {
        BookingModification entry = new BookingModification(ORIGINAL, 4, MOVED, 6, FIRST_CHANGE);
        List<BookingModification> suppliedHistory = new ArrayList<>();
        suppliedHistory.add(entry);
        Booking restored = new Booking(
            "bk-1", "r-1", "u-1", MOVED, 6, BookingStatus.CONFIRMED, CREATED, suppliedHistory);

        suppliedHistory.clear();

        assertEquals(List.of(entry), restored.history());
        assertThrows(UnsupportedOperationException.class, () -> restored.history().clear());
    }

    @Test
    void cancellationRetainsHistoryAndCurrentDetails() {
        Booking changed = booking().modified(MOVED, 6, FIRST_CHANGE);
        Booking cancelled = changed.cancelled();

        assertEquals(BookingStatus.CANCELLED, cancelled.status());
        assertEquals(changed.history(), cancelled.history());
        assertEquals(changed.interval(), cancelled.interval());
        assertEquals(changed.attendees(), cancelled.attendees());
        assertEquals(changed.id(), cancelled.id());
        assertEquals(changed.roomId(), cancelled.roomId());
        assertEquals(changed.userId(), cancelled.userId());
        assertEquals(changed.createdAt(), cancelled.createdAt());
        assertEquals(BookingStatus.CONFIRMED, changed.status());
    }

    @Test
    void invalidModificationDetailsLeaveTheOriginalUnchanged() {
        Booking original = booking();
        for (int attendees : new int[] {0, -1}) {
            assertThrows(IllegalArgumentException.class,
                () -> original.modified(MOVED, attendees, FIRST_CHANGE));
        }
        assertThrows(NullPointerException.class, () -> original.modified(null, 4, FIRST_CHANGE));
        assertThrows(NullPointerException.class, () -> original.modified(MOVED, 4, null));

        assertTrue(original.history().isEmpty());
        assertEquals(ORIGINAL, original.interval());
        assertEquals(4, original.attendees());
    }

    @Test
    void historyEntriesRequireDetailsAndPositiveCounts() {
        assertThrows(NullPointerException.class,
            () -> new BookingModification(null, 4, MOVED, 6, FIRST_CHANGE));
        assertThrows(NullPointerException.class,
            () -> new BookingModification(ORIGINAL, 4, null, 6, FIRST_CHANGE));
        assertThrows(NullPointerException.class,
            () -> new BookingModification(ORIGINAL, 4, MOVED, 6, null));
        for (int attendees : new int[] {0, -1}) {
            assertThrows(IllegalArgumentException.class,
                () -> new BookingModification(ORIGINAL, attendees, MOVED, 6, FIRST_CHANGE));
            assertThrows(IllegalArgumentException.class,
                () -> new BookingModification(ORIGINAL, 4, MOVED, attendees, FIRST_CHANGE));
        }
    }

    private static Booking booking() {
        return Booking.confirmed("bk-1", "r-1", "u-1", ORIGINAL, 4, CREATED);
    }
}
