import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/services/auth.guard';
import { ShellComponent } from './layout/shell.component';

export const routes: Routes = [
  { path: 'login', canActivate: [guestGuard], loadComponent: () => import('./features/login/login.page').then((m) => m.LoginPage), title: 'Logowanie · Narrivy' },
  { path: 'reset-password', loadComponent: () => import('./features/login/reset-password.page').then((m) => m.ResetPasswordPage), title: 'Nowe hasło · Narrivy' },
  { path: 'verify-email', loadComponent: () => import('./features/login/verify-email.page').then((m) => m.VerifyEmailPage), title: 'Potwierdzenie e-mail · Narrivy' },
  { path: 'terms', loadComponent: () => import('./features/legal/legal.page').then((m) => m.LegalPage), data: { doc: 'terms' }, title: 'Regulamin · Narrivy' },
  { path: 'privacy', loadComponent: () => import('./features/legal/legal.page').then((m) => m.LegalPage), data: { doc: 'privacy' }, title: 'Polityka prywatności · Narrivy' },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      // Po zalogowaniu użytkownik trafia od razu do edytora
      { path: '', pathMatch: 'full', loadComponent: () => import('./features/editor/start.page').then((m) => m.StartPage) },
      { path: 'editor/:id', loadComponent: () => import('./features/editor/editor.page').then((m) => m.EditorPage), title: 'Edytor · Narrivy' },
      { path: 'projects', loadComponent: () => import('./features/projects/projects.page').then((m) => m.ProjectsPage), title: 'Projekty · Narrivy' },
      { path: 'voices', loadComponent: () => import('./features/voices/voices.page').then((m) => m.VoicesPage), data: { tab: 'all' }, title: 'Głosy · Narrivy' },
      { path: 'voices/favorites', loadComponent: () => import('./features/voices/voices.page').then((m) => m.VoicesPage), data: { tab: 'favorites' }, title: 'Ulubione głosy · Narrivy' },
      { path: 'voices/mine', loadComponent: () => import('./features/voices/voices.page').then((m) => m.VoicesPage), data: { tab: 'mine' }, title: 'Moje głosy · Narrivy' },
      { path: 'voices/design', loadComponent: () => import('./features/voice-design/voice-design.page').then((m) => m.VoiceDesignPage), title: 'Stwórz głos · Narrivy' },
      { path: 'voices/clone', loadComponent: () => import('./features/voice-clone/voice-clone.page').then((m) => m.VoiceClonePage), title: 'Klonuj głos · Narrivy' },
      { path: 'voices/:id', loadComponent: () => import('./features/voices/voice-detail.page').then((m) => m.VoiceDetailPage), title: 'Głos · Narrivy' },
      { path: 'history', loadComponent: () => import('./features/history/history.page').then((m) => m.HistoryPage), title: 'Historia · Narrivy' },
      { path: 'settings', pathMatch: 'full', redirectTo: 'settings/profile' },
      { path: 'settings/:tab', loadComponent: () => import('./features/settings/settings.page').then((m) => m.SettingsPage), title: 'Ustawienia · Narrivy' },
    ],
  },
  { path: '**', redirectTo: '' },
];
