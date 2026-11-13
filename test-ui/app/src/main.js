/* boot */
(function boot() {
  shell();
  const h = location.hash.slice(1); state.screen = SCREENS[h] ? h : 'home';
  if (state.live) ensureLiveLogin();
  render(); renderLog(); if (!state.live) startSweeper();
})();
