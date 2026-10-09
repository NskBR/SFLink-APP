export function focusInput(node: HTMLInputElement) {
  node.focus(); node.select();
}
export function focusDialog(node: HTMLElement) {
  const previous = document.activeElement as HTMLElement | null;
  const targets = () => [...node.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), summary, [tabindex="0"]')].filter(el => el.offsetParent !== null);
  (node.querySelector<HTMLInputElement>('input:not(:disabled)') || targets()[0] || node).focus();
  function trap(event: KeyboardEvent) {
    if (event.key !== 'Tab') return;
    const items = targets();
    const first = items[0], last = items[items.length - 1];
    if (!first) { event.preventDefault(); node.focus(); return; }
    if (event.shiftKey && (document.activeElement === first || document.activeElement === node)) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }
  node.addEventListener('keydown', trap);
  return { destroy() { node.removeEventListener('keydown', trap); previous?.focus(); } };
}
