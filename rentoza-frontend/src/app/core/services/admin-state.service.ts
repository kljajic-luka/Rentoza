import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { map, shareReplay } from 'rxjs/operators';
import {
  AdminUserDto,
  AdminUserDetailDto,
  DashboardKpiDto,
  AdminCarDto,
  AdminApiService,
} from '../services/admin-api.service';

interface AdminState {
  dashboardKpi: DashboardKpiDto | null;
  users: AdminUserDto[];
  totalUsers: number;
  currentUser: AdminUserDetailDto | null;
  cars: AdminCarDto[];
  pendingCars: AdminCarDto[];
  loading: boolean;
  error: string | null;
}

const initialState: AdminState = {
  dashboardKpi: null,
  users: [],
  totalUsers: 0,
  currentUser: null,
  cars: [],
  pendingCars: [],
  loading: false,
  error: null,
};

@Injectable({ providedIn: 'root' })
export class AdminStateService {
  private state = new BehaviorSubject<AdminState>(initialState);

  // Selectors with replay to avoid unnecessary HTTP calls across subscribers
  dashboardKpi$: Observable<DashboardKpiDto | null> = this.state.pipe(
    map((s) => s.dashboardKpi),
    shareReplay(1)
  );

  users$: Observable<AdminUserDto[]> = this.state.pipe(
    map((s) => s.users),
    shareReplay(1)
  );

  totalUsers$: Observable<number> = this.state.pipe(
    map((s) => s.totalUsers),
    shareReplay(1)
  );

  currentUser$: Observable<AdminUserDetailDto | null> = this.state.pipe(
    map((s) => s.currentUser),
    shareReplay(1)
  );

  cars$: Observable<AdminCarDto[]> = this.state.pipe(
    map((s) => s.cars),
    shareReplay(1)
  );

  pendingCars$: Observable<AdminCarDto[]> = this.state.pipe(
    map((s) => s.pendingCars),
    shareReplay(1)
  );

  loading$: Observable<boolean> = this.state.pipe(
    map((s) => s.loading),
    shareReplay(1)
  );

  error$: Observable<string | null> = this.state.pipe(
    map((s) => s.error),
    shareReplay(1)
  );

  constructor(private api: AdminApiService) {}

  // Public actions
  loadDashboardKpis(): void {
    this.updateState({ loading: true, error: null });
    this.api.getDashboardKpis().subscribe({
      next: (kpis) => this.updateState({ dashboardKpi: kpis, loading: false }),
      error: () => this.updateState({ loading: false, error: 'Failed to load dashboard KPIs' }),
    });
  }

  loadUsers(page = 0, size = 20, search?: string): void {
    this.updateState({ loading: true, error: null });
    this.api.getUsers(page, size, search).subscribe({
      next: (response) =>
        this.updateState({
          users: response.content,
          totalUsers: response.totalElements ?? response.content.length,
          loading: false,
        }),
      error: () => this.updateState({ loading: false, error: 'Failed to load users' }),
    });
  }

  loadUserDetail(userId: number): void {
    this.updateState({ loading: true, error: null });
    this.api.getUserDetail(userId).subscribe({
      next: (user) => this.updateState({ currentUser: user, loading: false }),
      error: () => this.updateState({ loading: false, error: 'Failed to load user details' }),
    });
  }

  loadPendingCars(): void {
    this.updateState({ loading: true, error: null });
    this.api.getPendingCars().subscribe({
      next: (cars) => this.updateState({ pendingCars: cars, loading: false }),
      error: () => this.updateState({ loading: false, error: 'Failed to load pending cars' }),
    });
  }

  banUser(userId: number, reason: string): Observable<void> {
    return new Observable((subscriber) => {
      this.api.banUser(userId, reason).subscribe({
        next: () => {
          this.loadUserDetail(userId);
          subscriber.next();
          subscriber.complete();
        },
        error: (err) => {
          this.updateState({ error: 'Failed to ban user' });
          subscriber.error(err);
        },
      });
    });
  }

  unbanUser(userId: number): Observable<void> {
    return new Observable((subscriber) => {
      this.api.unbanUser(userId).subscribe({
        next: () => {
          this.loadUserDetail(userId);
          subscriber.next();
          subscriber.complete();
        },
        error: (err) => {
          this.updateState({ error: 'Failed to unban user' });
          subscriber.error(err);
        },
      });
    });
  }

  deleteUser(userId: number, reason: string): Observable<void> {
    return new Observable((subscriber) => {
      this.api.deleteUser(userId, reason).subscribe({
        next: () => {
          this.loadUsers();
          subscriber.next();
          subscriber.complete();
        },
        error: (err) => {
          this.updateState({ error: 'Failed to delete user' });
          subscriber.error(err);
        },
      });
    });
  }

  clearError(): void {
    this.updateState({ error: null });
  }

  private updateState(partial: Partial<AdminState>): void {
    this.state.next({ ...this.state.value, ...partial });
  }
}
