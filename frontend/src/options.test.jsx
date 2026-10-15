import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Analytics, Fraud, Channels } from './options.jsx';
import { ConfirmProvider } from './ui.jsx';
import { setLang } from './i18n.js';
import fs from 'node:fs';

setLang('fr');
const json = (body) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } }));
afterEach(() => vi.restoreAllMocks());

const OVERVIEW = {
  from: '2026-10-01', to: '2026-10-15',
  kpis: { grossCharged: 125.5, arpu: 2.51, payingSubscribers: 50, activeSubscriptions: 40, activations: 12, stops: 3, churnRate: 0.0698, moReceived: 900, mtDelivered: 850, fraudAlerts: 2 },
  revenueByDay: [{ day: '2026-10-14', events: 10, gross: 20, operatorShare: 8, partnerShare: 3, providerShare: 5, taxes: 4 }],
  revenueByOperator: [{ operator: 'TT', events: 10, gross: 20, operatorShare: 8, partnerShare: 3, providerShare: 5, taxes: 4 }],
  revenueByService: [], revenueByPartner: [], revenueByType: [],
  subscribers: { activeByService: [{ service: 'Horoscope', active: 40 }], movementsByDay: [] },
  channels: [{ channel: 'MO SMS', count: 900, detail: '' }, { channel: 'Sessions USSD', count: 30, detail: '' }],
};

describe('Décisionnel', () => {
  it('affiche les indicateurs, change d axe et garde un tableau équivalent au graphique', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((url) => json(String(url).startsWith('/admin/analytics/dwh/runs') ? [] : OVERVIEW));
    render(<Analytics />);
    expect(await screen.findByText('125.500 TND')).toBeInTheDocument();
    expect(screen.getByText('7.0 %')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: "Chiffre d'affaires facturé" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Par opérateur' }));
    expect(screen.getByRole('tab', { name: 'Par opérateur' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('TT')).toBeInTheDocument();
    expect(screen.getByText('Sessions USSD')).toBeInTheDocument();
    expect(screen.getByText('tables de production')).toBeInTheDocument();   // source des montants affichée
  });
});

describe('Antifraude', () => {
  it('liste les alertes et acquitte', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation((url, init) => {
      if (String(url).startsWith('/admin/fraud/alerts') && init?.method !== 'POST') return json([{ id: 7, createdAt: '2026-10-15T10:00:00Z', rule: 'VELOCITY_MINUTE', msisdn: '+21698***456', service: '', detail: 'plus de 10 MO en une minute', status: 'OPEN', acknowledgedBy: '' }]);
      if (String(url) === '/admin/fraud/blocks') return json([]);
      return Promise.resolve(new Response(null, { status: 204 }));
    });
    render(<ConfirmProvider><Fraud canWrite /></ConfirmProvider>);
    expect(await screen.findByText('VELOCITY_MINUTE')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Acquitter' }));
    await waitFor(() => expect(spy).toHaveBeenCalledWith('/admin/fraud/alerts/7/ack', expect.objectContaining({ method: 'POST' })));
  });
});

describe('Canaux', () => {
  it('sans droit d écriture : pas de création de menu', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => json([]));
    render(<ConfirmProvider><Channels canWrite={false} /></ConfirmProvider>);
    expect(await screen.findByText('Menus interactifs')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Créer' })).toBeNull();
  });
});

describe('Accès SMPP', () => {
  it('affiche le mot de passe généré une seule fois après attribution', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((url, init) => {
      const u = String(url);
      if (u === '/admin/smpp/clients' && init?.method !== 'POST') return json({ enabled: true, port: 2777, clients: [] });
      if (u.startsWith('/admin/api-clients')) return json([{ id: 3, name: 'agregateur', active: true }]);
      if (u === '/admin/smpp/clients/3' && init?.method === 'POST') return json({ systemId: 'agg1', password: 'Ab3dEf7h', port: 2777 });
      return json([]);
    });
    render(<ConfirmProvider><Channels canWrite isAdmin /></ConfirmProvider>);
    expect(await screen.findByText('Accès SMPP des clients')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('option', { name: 'agregateur' })).toBeInTheDocument());
    const form = screen.getByRole('button', { name: 'Attribuer' }).closest('form');
    await userEvent.selectOptions(form.querySelector('select'), '3');
    await userEvent.type(form.querySelector('input[type="text"]'), 'agg1');
    await userEvent.click(screen.getByRole('button', { name: 'Attribuer' }));
    expect(await screen.findByText('Ab3dEf7h')).toBeInTheDocument();
  });
});

describe('Application installable', () => {
  it('manifeste complet et service worker sans cache des API', () => {
    const m = JSON.parse(fs.readFileSync('public/manifest.webmanifest', 'utf8'));
    expect(m.display).toBe('standalone');
    expect(m.icons.map((i) => i.sizes)).toEqual(expect.arrayContaining(['192x192', '512x512']));
    expect(m.icons.some((i) => i.purpose === 'maskable')).toBe(true);
    const sw = fs.readFileSync('public/sw.js', 'utf8');
    expect(sw).toMatch(/admin\|api\|auth\|portal/);
  });
});
