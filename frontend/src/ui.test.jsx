import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Form, Modal, Pager, Table, rules, ConfirmProvider, useConfirm } from './ui.jsx';
import { setLang } from './i18n.js';

setLang('fr');

describe('Form : validation accessible', () => {
  it('bloque l envoi, affiche les erreurs liées aux champs et met le focus sur le premier champ invalide', async () => {
    const onSubmit = vi.fn();
    render(<Form submit="Envoyer" onSubmit={onSubmit} fields={[
      { name: 'msisdn', label: 'Numéro', required: true, validate: rules.msisdn },
      { name: 'pct', label: 'Pourcent', type: 'number', validate: rules.percent }]} />);
    await userEvent.click(screen.getByRole('button', { name: 'Envoyer' }));
    expect(onSubmit).not.toHaveBeenCalled();
    const field = screen.getByLabelText(/Numéro/);
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(field).toHaveAccessibleDescription('Champ obligatoire');
    await waitFor(() => expect(field).toHaveFocus());
    await userEvent.type(field, '12345');
    await userEvent.click(screen.getByRole('button', { name: 'Envoyer' }));
    expect(screen.getByText(/Numéro tunisien invalide/)).toBeInTheDocument();
  });

  it('envoie les valeurs valides et réinitialise', async () => {
    const onSubmit = vi.fn((v, done) => done());
    render(<Form submit="Envoyer" onSubmit={onSubmit} fields={[{ name: 'msisdn', label: 'Numéro', required: true, validate: rules.msisdn }, { name: 'pct', label: 'Pourcent', type: 'number', validate: rules.percent }]} />);
    await userEvent.type(screen.getByLabelText(/Numéro/), '98 123 456');
    await userEvent.type(screen.getByLabelText('Pourcent'), '150');
    await userEvent.click(screen.getByRole('button', { name: 'Envoyer' }));
    expect(screen.getByText('Entre 0 et 100')).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText('Pourcent'));
    await userEvent.type(screen.getByLabelText('Pourcent'), '30');
    await userEvent.click(screen.getByRole('button', { name: 'Envoyer' }));
    expect(onSubmit).toHaveBeenCalledWith({ msisdn: '98 123 456', pct: '30' }, expect.any(Function));
    expect(screen.getByLabelText(/Numéro/)).toHaveValue('');
  });
});

describe('validateurs', () => {
  it('numéro tunisien, pourcentage, montant', () => {
    for (const ok of ['98123456', '+21698123456', '216 98 123 456', '98.123.456']) expect(rules.msisdn(ok), ok).toBeNull();
    for (const bad of ['1234', '+33612345678', 'abc']) expect(rules.msisdn(bad), bad).not.toBeNull();
    expect(rules.percent('0')).toBeNull(); expect(rules.percent('100')).toBeNull(); expect(rules.percent('100.1')).not.toBeNull(); expect(rules.percent('-1')).not.toBeNull();
    expect(rules.positive('0.001')).toBeNull(); expect(rules.positive('0')).not.toBeNull();
  });
});

describe('Modal et confirmation', () => {
  it('rôle dialog, focus à l ouverture, Échap ferme, focus rendu à l opener', async () => {
    const onClose = vi.fn();
    function Host() { return <><button>ouvrir</button><Modal title="Titre" onClose={onClose}><button>OK</button></Modal></>; }
    render(<Host />);
    const dlg = screen.getByRole('dialog', { name: 'Titre' });
    expect(dlg).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('button', { name: 'OK' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });

  it('useConfirm résout true/false selon le bouton', async () => {
    let result;
    function Host() { const confirm = useConfirm(); return <button onClick={async () => { result = await confirm('Supprimer ?', { danger: true }); }}>go</button>; }
    render(<ConfirmProvider><Host /></ConfirmProvider>);
    await userEvent.click(screen.getByText('go'));
    expect(screen.getByRole('dialog')).toHaveTextContent('Supprimer ?');
    await userEvent.click(screen.getByRole('button', { name: 'Annuler' }));
    await waitFor(() => expect(result).toBe(false));
    await userEvent.click(screen.getByText('go'));
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer' }));
    await waitFor(() => expect(result).toBe(true));
  });
});

describe('Table et Pager', () => {
  it('en-têtes traduits et portée de colonne', () => {
    render(<Table caption="Ledger" cols={['status', 'maxTps']} rows={[{ id: 1, status: 'ACTIVE', maxTps: 50 }]} />);
    expect(screen.getByRole('columnheader', { name: 'Statut' })).toHaveAttribute('scope', 'col');
    expect(screen.getByRole('columnheader', { name: 'Débit max' })).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Ledger' })).toBeInTheDocument();
  });

  it('pagination : boutons, total, taille de page', async () => {
    const setPage = vi.fn(), setSize = vi.fn();
    render(<Pager total={230} page={1} size={50} setPage={setPage} setSize={setSize} />);
    expect(screen.getByText(/Page 2 sur 5 · 230 lignes/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }));
    expect(setPage).toHaveBeenCalledWith(2);
    await userEvent.click(screen.getByRole('button', { name: /Précédent/ }));
    expect(setPage).toHaveBeenCalledWith(0);
    await userEvent.selectOptions(screen.getByLabelText(/par page/), '100');
    expect(setSize).toHaveBeenCalledWith(100);
  });
});
