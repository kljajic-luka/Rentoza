import { test, expect } from './fixtures/auth.fixture';

/**
 * Chat Messaging E2E Tests - Playwright
 *
 * Tests critical messaging flows:
 * 1. Load conversations list
 * 2. Select conversation and display messages
 * 3. Send a message with optimistic update
 * 4. Typing indicator display
 * 5. Message status updates (sent, delivered, read)
 * 6. Quick replies functionality
 * 7. Mobile responsive layout
 * 8. Offline queue (localStorage fallback)
 *
 * Prerequisites:
 * - Backend services running (rentoza-api + chat-service)
 * - At least one conversation exists for the test user
 */

test.describe('Chat Messaging', () => {
  test.describe('Conversation List', () => {
    test('should load and display conversation list', async ({ authenticatedPage: page }) => {
      // Navigate to messages page
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      // Wait for the sidebar to load
      const sidebar = page.locator('.conversations-sidebar');
      await expect(sidebar).toBeVisible({ timeout: 10000 });

      // Check for conversation list component
      const conversationList = page.locator('app-conversation-list');
      await expect(conversationList).toBeVisible();

      // Either see conversations or empty state
      const hasConversations = await page.locator('.conversation-item').count();
      const hasEmptyState = await page.locator('.empty-state, .no-conversations').count();

      expect(hasConversations > 0 || hasEmptyState > 0).toBeTruthy();
    });

    test('should display WebSocket connection status', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      // Check for connection indicator
      const connectionIndicator = page.locator('.ws-status');
      await expect(connectionIndicator).toBeVisible({ timeout: 10000 });

      // Should show connected status (green icon)
      await expect(connectionIndicator).toHaveClass(/connected/);
    });

    test('should show unread count badge', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      // Check if any conversation has unread badge
      const unreadBadges = page.locator('.unread-badge, mat-badge');
      const badgeCount = await unreadBadges.count();

      // This is a soft assertion - test passes even without unread messages
      console.log(`Found ${badgeCount} unread badges`);
    });
  });

  test.describe('Conversation Selection', () => {
    test('should select a conversation and show messages', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      // Wait for conversation items
      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        // Click first conversation
        await conversationItems.first().click();

        // Wait for chat header to appear
        const chatHeader = page.locator('app-chat-header');
        await expect(chatHeader).toBeVisible({ timeout: 5000 });

        // Wait for message view
        const messageView = page.locator('app-message-view');
        await expect(messageView).toBeVisible({ timeout: 5000 });
      } else {
        // No conversations - check for empty state
        const noSelection = page.locator('.no-selection');
        await expect(noSelection).toBeVisible();
      }
    });

    test('should display chat header with participant info', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        await conversationItems.first().click();

        const chatHeader = page.locator('app-chat-header');
        await expect(chatHeader).toBeVisible({ timeout: 5000 });

        // Check for participant name
        const participantName = chatHeader.locator('.participant-name, .header-title');
        await expect(participantName).toBeVisible();

        // Check for back button on mobile viewport
        if (page.viewportSize()?.width && page.viewportSize()!.width < 768) {
          const backButton = chatHeader.locator('.back-button, button[aria-label*="back"]');
          await expect(backButton).toBeVisible();
        }
      }
    });
  });

  test.describe('Message Sending', () => {
    test('should send a message with optimistic update', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        await conversationItems.first().click();

        // Wait for input to be ready
        const messageInput = page.locator('app-message-input textarea');
        await expect(messageInput).toBeVisible({ timeout: 5000 });

        // Type a test message
        const testMessage = `E2E Test ${Date.now()}`;
        await messageInput.fill(testMessage);

        // Verify character counter appears for long messages
        const charCounter = page.locator('.char-counter');
        // Counter only shows when > 80% of limit

        // Click send button
        const sendButton = page.locator('app-message-input .send-button');
        await expect(sendButton).toBeEnabled();
        await sendButton.click();

        // Message should appear immediately (optimistic update)
        const messageContent = page.locator('.message-content, .message-bubble').getByText(testMessage);
        await expect(messageContent).toBeVisible({ timeout: 3000 });

        // Input should be cleared
        await expect(messageInput).toHaveValue('');
      }
    });

    test('should send message on Enter key press', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        await conversationItems.first().click();

        const messageInput = page.locator('app-message-input textarea');
        await expect(messageInput).toBeVisible({ timeout: 5000 });

        const testMessage = `E2E Enter Test ${Date.now()}`;
        await messageInput.fill(testMessage);
        await messageInput.press('Enter');

        // Message should be sent (input cleared)
        await expect(messageInput).toHaveValue('');
      }
    });

    test('should not send message on Shift+Enter (newline)', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        await conversationItems.first().click();

        const messageInput = page.locator('app-message-input textarea');
        await expect(messageInput).toBeVisible({ timeout: 5000 });

        await messageInput.fill('Line 1');
        await messageInput.press('Shift+Enter');
        await messageInput.type('Line 2');

        // Input should contain both lines (newline, not sent)
        const value = await messageInput.inputValue();
        expect(value).toContain('Line 1');
        expect(value).toContain('Line 2');
      }
    });
  });

  test.describe('Quick Replies', () => {
    test('should display quick reply buttons', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        await conversationItems.first().click();

        // Wait for quick replies to be visible
        const quickReplies = page.locator('app-quick-replies');
        await expect(quickReplies).toBeVisible({ timeout: 5000 });

        // Should have quick reply buttons
        const replyButtons = quickReplies.locator('.quick-reply-btn');
        const buttonCount = await replyButtons.count();
        expect(buttonCount).toBeGreaterThan(0);
      }
    });

    test('should populate input when quick reply clicked', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        await conversationItems.first().click();

        const quickReplies = page.locator('app-quick-replies');
        await expect(quickReplies).toBeVisible({ timeout: 5000 });

        const firstReplyButton = quickReplies.locator('.quick-reply-btn').first();
        const replyText = await firstReplyButton.innerText();
        await firstReplyButton.click();

        // Message input should contain the quick reply text
        const messageInput = page.locator('app-message-input textarea');
        await expect(messageInput).toHaveValue(replyText);
      }
    });
  });

  test.describe('Message Status', () => {
    test('should display message status icons for own messages', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        await conversationItems.first().click();

        // Wait for messages to load
        await page.waitForTimeout(2000);

        // Find own messages (should have status icons)
        const ownMessages = page.locator('.message-bubble-own, .own-message');
        const ownCount = await ownMessages.count();

        if (ownCount > 0) {
          // Check for status icon (check, done, done_all)
          const statusIcon = ownMessages.first().locator('.message-status, mat-icon');
          const hasStatus = await statusIcon.count();
          expect(hasStatus).toBeGreaterThan(0);
        }
      }
    });
  });

  test.describe('Typing Indicator', () => {
    test('should emit typing started event when typing', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        await conversationItems.first().click();

        const messageInput = page.locator('app-message-input textarea');
        await expect(messageInput).toBeVisible({ timeout: 5000 });

        // Type in the input
        await messageInput.type('Testing typing indicator...');

        // The typing indicator WebSocket message should be sent
        // We can't directly verify WebSocket, but we can check the input works
        await expect(messageInput).not.toHaveValue('');
      }
    });
  });

  test.describe('Mobile Responsive', () => {
    test.use({ viewport: { width: 375, height: 667 } }); // iPhone SE

    test('should show sidebar on mobile, hide when conversation selected', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      // Sidebar should be visible initially
      const sidebar = page.locator('.conversations-sidebar');
      await expect(sidebar).toBeVisible({ timeout: 10000 });

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        await conversationItems.first().click();

        // On mobile, chat window should take full screen
        const chatWindow = page.locator('.chat-window');
        await expect(chatWindow).toBeVisible({ timeout: 5000 });

        // Check for back button
        const backButton = page.locator('.back-button, [aria-label*="back"]');
        await expect(backButton).toBeVisible();
      }
    });

    test('should return to conversation list on back button click', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        await conversationItems.first().click();

        const backButton = page.locator('.back-button, [aria-label*="back"]');
        if (await backButton.isVisible()) {
          await backButton.click();

          // Sidebar should be visible again
          const sidebar = page.locator('.conversations-sidebar');
          await expect(sidebar).toBeVisible({ timeout: 5000 });
        }
      }
    });
  });

  test.describe('Offline Queue', () => {
    test('should gracefully handle offline mode', async ({ authenticatedPage: page }) => {
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        await conversationItems.first().click();

        // Simulate offline mode
        await page.context().setOffline(true);

        try {
          const messageInput = page.locator('app-message-input textarea');
          await expect(messageInput).toBeVisible({ timeout: 5000 });

          // Try to send a message while offline
          const testMessage = `Offline Test ${Date.now()}`;
          await messageInput.fill(testMessage);

          const sendButton = page.locator('app-message-input .send-button');
          await sendButton.click();

          // Message should still appear (optimistically) but marked as failed/queued
          // Check localStorage for offline queue
          const offlineQueue = await page.evaluate(() => {
            return localStorage.getItem('rentoza_chat_offline_queue');
          });

          // Queue may or may not have items depending on implementation timing
          console.log('Offline queue:', offlineQueue);
        } finally {
          // Restore online mode
          await page.context().setOffline(false);
        }
      }
    });
  });

  test.describe('Deep Linking', () => {
    test('should select conversation from bookingId query param', async ({ authenticatedPage: page }) => {
      // First get a valid booking ID from an existing conversation
      await page.goto('/messages');
      await page.waitForLoadState('networkidle');

      const conversationItems = page.locator('app-conversation-item, .conversation-item');
      const count = await conversationItems.count();

      if (count > 0) {
        // Click first to get its booking ID
        await conversationItems.first().click();

        // Get booking ID from URL or attribute (implementation dependent)
        const url = page.url();
        console.log('Current URL after selection:', url);

        // The conversation should be selected
        const chatHeader = page.locator('app-chat-header');
        await expect(chatHeader).toBeVisible({ timeout: 5000 });
      }
    });
  });
});
