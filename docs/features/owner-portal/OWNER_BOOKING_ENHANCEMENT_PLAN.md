# Owner Booking Experience Enhancement Plan

**Objective:** Elevate the Owner Booking management experience to "Turo-grade" standards by enriching the booking card UI and implementing a comprehensive Booking Details Dialog.

**Target Audience:** Vehicle Owners (Hosts)
**Goal:** Provide hosts with immediate, actionable insights and deep-dive capabilities for their bookings.

---

## 1. Owner Booking Card Redesign (`OwnerBookingsComponent`)

The current card is functional but visually sparse. We will transform it into a rich, information-dense dashboard widget.

### 1.1 Visual Overhaul
*   **Car Thumbnail:** Add the vehicle's primary image to the left side of the card (parity with Renter view).
*   **Status Badges:** Use the enhanced status badges (colors + tooltips) from the Renter view.
*   **Typography:** Fix the "Model Model" bug. Use distinct typography for "Net Earnings" vs "Total Price".

### 1.2 Content Enrichment
*   **Financial Snapshot:**
    *   **Primary:** Display **Net Earnings** (Your Payout) prominently in green.
    *   **Secondary:** Display "Gross Total" (what renter pays) in smaller, neutral text.
*   **Renter Insight:**
    *   Show Renter's Avatar (circle) + Name.
    *   Show Renter's Star Rating (if available).
*   **Timeline Indicators:**
    *   **Upcoming:** "Starts in 2 days" (Countdown).
    *   **Active:** "Ends in 4 hours" (Countdown).
    *   **Past:** "Ended 2 days ago".

### 1.3 Action Bar
*   **Primary Action:** Context-aware (e.g., "Check-in", "Review Renter").
*   **Secondary Action:** "Booking Details" (Opens new dialog).
*   **Tertiary Action:** "Cancel" (if applicable).

---

## 2. New Component: `OwnerBookingDetailsDialog`

A dedicated modal for deep-diving into a specific booking. This will be far more detailed than the Renter's version.

### 2.1 Architecture
*   **Component:** `OwnerBookingDetailsDialogComponent`
*   **Route/Trigger:** Opened via "Booking Details" button on the card.

### 2.2 Layout & Sections (Tabs or Sections)

#### A. Overview (Header)
*   **Status Banner:** Large, color-coded status indicator.
*   **Reference ID:** Booking ID for support.
*   **Dates:** Clear Start -> End timeline with duration (e.g., "3 Days").

#### B. Renter Profile (The "Who")
*   **Profile Card:** Large Avatar, Name, Age.
*   **Trust Signals:** Verified Phone, Verified ID badge.
*   **Contact:** "Message Renter" button (direct link to chat).

#### C. Financial Breakdown (The "Money")
*   **Detailed Ledger:**
    *   (+) Daily Rate x Days
    *   (+) Delivery Fee
    *   (+) Extras (Child seat, etc.)
    *   **(=) Gross Total**
    *   (-) Platform Fee (e.g., 20%)
    *   **(=) Net Earnings (Your Payout)**
*   *Note: This provides transparency on how much the owner actually keeps.*

#### D. Trip Timeline (The "When")
*   **Vertical Stepper:**
    1.  **Request Created:** [Date/Time]
    2.  **Approved:** [Date/Time]
    3.  **Check-in Started:** [Date/Time]
    4.  **Trip Started:** [Date/Time]
    5.  **Trip Ended:** [Date/Time]

---

## 3. Implementation Steps

### Step 1: Create the Dialog Component
*   Generate `OwnerBookingDetailsDialogComponent`.
*   Implement `loadBookingDetails()` to fetch full data (including financial breakdown).

### Step 2: Upgrade the Card (`OwnerBookingsComponent`)
*   **HTML:** Refactor `mat-card` structure to grid layout (Image | Info | Actions).
*   **TS:** Add helper methods for `getNetEarnings()`, `getTimeUntil()`.
*   **CSS:** Apply "Turo-style" styling (clean whites, bold typography, subtle shadows).

### Step 3: Integrate
*   Connect the "Details" button in the card to open the new dialog.

---

## 4. Technical Considerations

*   **Security:** Ensure `booking.renter` data in the response is sanitized (no sensitive PII like full address unless confirmed).
*   **Performance:** Use `OnPush` change detection for the new dialog.
*   **Reusability:** Reuse `StatusBadge` and `UserAvatar` components if they exist, or create them.

## 5. Approval Request

Please confirm if this plan aligns with your vision for the "Owner Dashboard" upgrade.
