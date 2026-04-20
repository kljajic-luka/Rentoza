# Post-Host Check-in Implementation Plan

## Context
Currently, after the Host submits their check-in (photos, fuel, odometer), the frontend remains on the Host Check-in form or incorrectly transitions to the Guest Check-in form. This creates confusion and allows the Host to potentially edit data that should be locked, or see screens intended for the Guest.

## Objective
Implement a dedicated "Waiting for Guest" state for the Host after they have successfully submitted their check-in. The Host should see a status screen indicating that their part is done and they are waiting for the Guest to review and acknowledge the vehicle condition.

## User Flow
1. **Host Submission**: Host clicks "Submit" on `HostCheckInComponent`.
2. **Success Feedback**: A snackbar confirms success (already implemented).
3. **State Transition**: The wizard detects the new state CHECK_IN_HOST_COMPLETE which is already posted in the db for that booking in the bookings table under status column.
4. **Role Check**: The wizard checks if the current user is the **Host**.
5. **Waiting Screen**: Instead of showing the Guest Check-in component, the wizard displays a `CheckInWaitingComponent` with a message:
   - "Vaš deo je završen. Čeka se pregled gosta." (Your part is complete. Waiting for guest review.)
   - Shows a summary of what's next (Guest review -> Handshake).
   - Optional: "Back to Bookings" button.

## Technical Implementation

### 1. New Component: `CheckInWaitingComponent`
Create a new standalone component `features/bookings/check-in/check-in-waiting.component.ts`.

**Features:**
- **Inputs**:
  - `message`: Main status message.
  - `subMessage`: Explanatory text.
  - `step`: Current step in the process (e.g., 'GUEST_REVIEW').
- **UI**:
  - Icon (e.g., `hourglass_empty` or `check_circle`).
  - Title and description.
  - Timeline/Stepper visualization (optional, as the main wizard has one).
  - "Refresh Status" button.
  - "Go to Booking Details" button.

### 2. Update `CheckInWizardComponent`
Modify `src/app/features/bookings/check-in/check-in-wizard.component.ts`.

**Logic Changes:**
- **Identify Role**: Use `checkInService.currentStatus()?.host` and `checkInService.currentStatus()?.guest` to determine the viewer's role.
- **Conditional Template**:
  - **Case: Host View**
    - If Phase is `HOST_PHASE`: Show `HostCheckInComponent`.
    - If Phase is `GUEST_PHASE`: **Show `CheckInWaitingComponent`** (Waiting for Guest).
    - If Phase is `HANDSHAKE`: Show `HandshakeComponent`.
  - **Case: Guest View**
    - If Phase is `HOST_PHASE`: **Show `CheckInWaitingComponent`** (Waiting for Host).
    - If Phase is `GUEST_PHASE`: Show `GuestCheckInComponent`.
    - If Phase is `HANDSHAKE`: Show `HandshakeComponent`.

**Code Snippet (Conceptual):**
```typescript
// In template
@switch (checkInService.currentPhase()) {
  @case ('HOST_PHASE') {
    @if (isHost()) {
      <app-host-check-in ... />
    } @else {
      <app-check-in-waiting 
         message="Domaćin priprema vozilo" 
         subMessage="Obavestićemo vas kada check-in bude spreman." 
      />
    }
  }
  @case ('GUEST_PHASE') {
    @if (isGuest()) {
      <app-guest-check-in ... />
    } @else {
      <app-check-in-waiting 
         message="Čeka se pregled gosta" 
         subMessage="Gost treba da potvrdi stanje vozila."
      />
    }
  }
  // ...
}
```

### 3. Backend Considerations
No backend changes are strictly required as the `CheckInStatusDTO` already provides the necessary state (`status`) and role flags (`host`, `guest`).

## Verification Plan
1. **Manual Test (Host)**:
   - Log in as Host.
   - Open Check-in for a booking in `CHECK_IN_OPEN` state.
   - Complete photos and form.
   - Submit.
   - **Verify**: Screen transitions to "Waiting for Guest" instead of Guest Check-in.
2. **Manual Test (Guest)**:
   - Log in as Guest for the same booking.
   - **Verify**: Sees Guest Check-in form.
   - Complete Guest acknowledgment.
   - **Verify**: Both see Handshake screen.

## Next Steps
1. Create `CheckInWaitingComponent`.
2. Integrate into `CheckInWizardComponent`.
3. Verify flows for both Host and Guest.
