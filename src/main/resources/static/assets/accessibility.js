"use strict";

(() => {
  let lastFocus;
  document.addEventListener("focusin", event => {
    if (event.target !== document.body) lastFocus = event.target;
  });
  const canFocus = element => element?.isConnected && !element.disabled &&
    !element.closest("[hidden], [inert]") && element.getClientRects().length > 0;
  document.addEventListener("click", event => {
    const link = event.target.closest('a.skip-link[href="#main"]');
    const main = document.querySelector("#main");
    if (!link || !main) return;
    // Keep the current product/cart hash; a skip link is not a route change.
    event.preventDefault(); main.focus(); main.scrollIntoView({ block: "start" });
  });
  document.addEventListener("keydown", event => {
    if (event.key !== "Tab") return;
    const dialogs = [...document.querySelectorAll("dialog[open]")];
    const dialog = dialogs.at(-1);
    if (!dialog) return;
    const controls = [...dialog.querySelectorAll('a[href], button, input, select, textarea, [tabindex]')]
      .filter(element => element.tabIndex >= 0 && canFocus(element));
    const first = controls[0], last = controls.at(-1);
    if (!first) { event.preventDefault(); return; }
    if (event.shiftKey && (document.activeElement === first || !dialog.contains(document.activeElement))) {
      event.preventDefault(); last.focus();
    } else if (!event.shiftKey && (document.activeElement === last || !dialog.contains(document.activeElement))) {
      event.preventDefault(); first.focus();
    }
  });

  window.StockroomFocus = {
    open(dialog, initial) {
      if (dialog.open) return;
      const opener = document.activeElement === document.body ? lastFocus : document.activeElement;
      dialog.addEventListener("close", () => requestAnimationFrame(() => {
        // A nested confirmation can close while its parent dialog stays open.
        const activeDialog = document.querySelector("dialog[open]");
        if (activeDialog && !activeDialog.contains(opener)) return;
        const current = document.activeElement;
        if (current !== document.body && current !== dialog && !dialog.contains(current) && canFocus(current)) return;
        if (canFocus(opener)) opener.focus({ preventScroll: true });
        else if (!activeDialog && (!canFocus(document.activeElement) || document.activeElement === document.body)) {
          const target = document.querySelector("main section:not([hidden]) h1, main h1, main");
          if (target) { target.tabIndex = -1; target.focus({ preventScroll: true }); }
        }
      }), { once: true });
      dialog.showModal();
      if (initial && canFocus(dialog.querySelector(initial))) dialog.querySelector(initial).focus();
    },
  };
})();
