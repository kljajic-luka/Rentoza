import { Directive, Input, OnDestroy, TemplateRef, ViewContainerRef } from '@angular/core';
import { Subscription } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { UserRole } from '@core/models/user-role.type';

@Directive({
  selector: '[appHasRole]',
  standalone: true
})
export class HasRoleDirective implements OnDestroy {
  private readonly subscription: Subscription;
  private requiredRoles: UserRole[] = [];
  private hasView = false;

  constructor(
    private readonly templateRef: TemplateRef<unknown>,
    private readonly viewContainer: ViewContainerRef,
    private readonly authService: AuthService
  ) {
    this.subscription = this.authService.currentUser$.subscribe(() => this.updateView());
  }

  @Input()
  set appHasRole(value: UserRole | UserRole[]) {
    this.requiredRoles = Array.isArray(value) ? value : [value];
    this.updateView();
  }

  ngOnDestroy(): void {
    this.subscription.unsubscribe();
  }

  private updateView(): void {
    const canShow =
      !this.requiredRoles.length || this.authService.hasAnyRole(this.requiredRoles);

    if (canShow && !this.hasView) {
      this.viewContainer.createEmbeddedView(this.templateRef);
      this.hasView = true;
      return;
    }

    if (!canShow && this.hasView) {
      this.viewContainer.clear();
      this.hasView = false;
    }
  }
}