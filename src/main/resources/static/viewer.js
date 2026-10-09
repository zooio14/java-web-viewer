'use strict';

(() => {
  const ui = Object.fromEntries([
    'screen', 'screen-shell', 'welcome', 'screen-notice', 'login-form', 'token', 'connect',
    'disconnect', 'connection-dot', 'connection-label', 'address-form', 'address',
    'text-form', 'remote-text', 'allowed-domains', 'domain-count', 'status', 'status-hint'
  ].map(id => [id, document.getElementById(id)]));
  const canvas = ui.screen;
  const context = canvas.getContext('2d', { alpha: false });
  const controls = document.querySelectorAll('.remote-control');
  const MAX_BUFFERED_BYTES = 128 * 1024;
  const specialKeys = new Set(['Control', 'Alt', 'Shift', 'Meta', 'Backspace', 'Tab', 'Enter',
    'Escape', 'Delete', 'Insert', 'Home', 'End', 'PageUp', 'PageDown', 'ArrowUp', 'ArrowDown',
    'ArrowLeft', 'ArrowRight', 'CapsLock', 'F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F8', 'F9', 'F10', 'F11', 'F12']);
  const pressedKeys = new Set();
  const pressedButtons = new Set();
  let socket = null;
  let sessionRequest = null;
  let active = false;
  let connecting = false;
  let generation = 0;
  let pendingMove = null;
  let moveTimer = null;
  let wheelTimer = null;
  let wheelX = 0;
  let wheelY = 0;
  let lastPoint = { x: 0, y: 0 };

  function status(message, error = false) {
    ui.status.textContent = message;
    ui.status.classList.toggle('error', error);
  }

  function setConnection(mode) {
    active = mode === 'connected';
    const closing = mode === 'closing';
    connecting = mode === 'connecting' || closing;
    ui['connection-label'].textContent = active ? 'Sessão conectada' : closing ? 'Encerrando…' : connecting ? 'Conectando…' : 'Desconectado';
    ui['connection-dot'].classList.toggle('connected', active);
    ui['connection-dot'].classList.toggle('connecting', connecting);
    controls.forEach(control => { control.disabled = !active; });
    ui.address.disabled = !active;
    ui['remote-text'].disabled = !active;
    ui.disconnect.disabled = closing || !(active || connecting);
    ui.connect.disabled = connecting || active;
    ui.token.disabled = connecting || active;
    if (mode === 'disconnected') {
      canvas.hidden = true;
      ui.welcome.hidden = false;
      ui['screen-notice'].hidden = true;
      ui['screen-shell'].classList.remove('has-screen');
      context.fillStyle = '#ffffff';
      context.fillRect(0, 0, canvas.width, canvas.height);
      ui.address.value = '';
      ui['remote-text'].value = '';
      document.title = 'Java Web Viewer · Navegador remoto';
    }
  }

  function applyConfig(config) {
    if (Number.isInteger(config.width) && Number.isInteger(config.height)
      && config.width > 0 && config.width <= 4096 && config.height > 0 && config.height <= 4096) {
      // Reassigning either dimension clears an already rendered frame.
      if (canvas.width !== config.width) canvas.width = config.width;
      if (canvas.height !== config.height) canvas.height = config.height;
    }
    if (Array.isArray(config.allowedDomains)) {
      ui['allowed-domains'].replaceChildren();
      config.allowedDomains.forEach(domain => {
        const item = document.createElement('li');
        item.textContent = String(domain);
        ui['allowed-domains'].append(item);
      });
      ui['domain-count'].textContent = String(config.allowedDomains.length);
      if (config.allowedDomains.length === 0) {
        const item = document.createElement('li');
        item.textContent = 'Nenhum domínio autorizado.';
        ui['allowed-domains'].append(item);
      }
    }
    if (Number.isInteger(config.idleTimeoutSeconds)) {
      ui['status-hint'].textContent = `Sessão encerra após ${config.idleTimeoutSeconds}s sem interação · Clique na tela para usar o teclado`;
    }
  }

  function send(message, allowBeforeReady = false) {
    if ((!active && !allowBeforeReady) || !socket || socket.readyState !== WebSocket.OPEN) return false;
    if (socket.bufferedAmount > MAX_BUFFERED_BYTES) {
      status('Conexão ocupada. Aguarde a transmissão alcançar a tela.');
      return false;
    }
    socket.send(JSON.stringify(message));
    return true;
  }

  function flushMove() {
    if (moveTimer !== null) clearTimeout(moveTimer);
    moveTimer = null;
    if (pendingMove) send({ type: 'pointer', action: 'move', ...pendingMove, button: 0 });
    pendingMove = null;
  }

  function releaseInput() {
    flushMove();
    pressedKeys.forEach(key => send({ type: 'key', action: 'up', key }));
    pressedButtons.forEach(button => send({ type: 'pointer', action: 'up', ...lastPoint, button }));
    pressedKeys.clear();
    pressedButtons.clear();
  }

  function clearTimers() {
    if (moveTimer !== null) clearTimeout(moveTimer);
    if (wheelTimer !== null) clearTimeout(wheelTimer);
    moveTimer = wheelTimer = null;
    pendingMove = null;
    wheelX = wheelY = 0;
    pressedKeys.clear();
    pressedButtons.clear();
  }

  async function logout() {
    try {
      await fetch('/api/logout', { method: 'POST', credentials: 'same-origin', body: '' });
    } catch (_) { /* Socket cleanup and expiry also remove the server session. */ }
  }

  async function disconnect() {
    releaseInput();
    const currentGeneration = ++generation;
    const previous = socket;
    socket = null;
    if (previous) previous.close(1000, 'Sessão encerrada pelo usuário.');
    clearTimers();
    setConnection('closing');
    status('Encerrando sessão…');
    if (sessionRequest) await sessionRequest.catch(() => {});
    await logout();
    if (generation !== currentGeneration) return;
    setConnection('disconnected');
    status('Sessão encerrada. Os dados do navegador foram descartados.');
  }

  async function paintFrame(blob, currentSocket, currentGeneration) {
    let bitmap = null;
    let objectUrl = null;
    try {
      if (typeof createImageBitmap === 'function') {
        bitmap = await createImageBitmap(blob);
      } else {
        objectUrl = URL.createObjectURL(blob);
        bitmap = new Image();
        bitmap.src = objectUrl;
        await bitmap.decode();
      }
      if (socket !== currentSocket || generation !== currentGeneration || !active) return;
      context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      canvas.hidden = false;
      ui.welcome.hidden = true;
      ui['screen-notice'].hidden = true;
      ui['screen-shell'].classList.add('has-screen');
    } catch (_) {
      if (socket === currentSocket) status('Não foi possível exibir um quadro. Aguardando o próximo.', true);
    } finally {
      if (bitmap && typeof bitmap.close === 'function') bitmap.close();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      if (socket === currentSocket && generation === currentGeneration) send({ type: 'frameAck' });
    }
  }

  function openSocket(currentGeneration) {
    const url = new URL('/ws/browser', location.href);
    url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const currentSocket = new WebSocket(url);
    currentSocket.binaryType = 'blob';
    socket = currentSocket;
    currentSocket.addEventListener('message', event => {
      if (socket !== currentSocket || generation !== currentGeneration) return;
      if (event.data instanceof Blob) {
        void paintFrame(event.data, currentSocket, currentGeneration);
        return;
      }
      let message;
      try { message = JSON.parse(event.data); } catch (_) { return; }
      if (message.type === 'ready') {
        applyConfig(message);
        setConnection('connected');
        ui.welcome.hidden = true;
        ui['screen-notice'].hidden = false;
        ui['screen-notice'].textContent = 'Navegador pronto. Abra um domínio autorizado na barra acima.';
        status('Sessão conectada. Abra um endereço autorizado.');
        ui.address.focus();
      } else if (message.type === 'location') {
        if (typeof message.url === 'string') ui.address.value = message.url === 'about:blank' ? '' : message.url;
        document.title = typeof message.title === 'string' && message.title ? `${message.title} · Web Viewer` : 'Java Web Viewer · Navegador remoto';
        status(message.url === 'about:blank' ? 'Navegador pronto. Abra um endereço autorizado.' : 'Página carregada. Clique na tela para interagir.');
      } else if (message.type === 'error' || message.type === 'status') {
        status(typeof message.message === 'string' ? message.message : 'O servidor atualizou a sessão.', message.type === 'error');
      } else if (message.type === 'ping') {
        send({ type: 'pong' }, true);
      }
    });
    currentSocket.addEventListener('error', () => {
      if (socket === currentSocket) status('Falha na conexão com o navegador remoto.', true);
    });
    currentSocket.addEventListener('close', async event => {
      if (socket !== currentSocket || generation !== currentGeneration) return;
      socket = null;
      clearTimers();
      setConnection('closing');
      const message = event.reason || 'Conexão encerrada. Conecte novamente para iniciar uma nova sessão.';
      status(message, event.code !== 1000);
      await logout();
      if (generation !== currentGeneration) return;
      setConnection('disconnected');
      status(message, event.code !== 1000);
    });
  }

  ui['login-form'].addEventListener('submit', async event => {
    event.preventDefault();
    if (connecting || active) return;
    let token = ui.token.value;
    ui.token.value = '';
    const currentGeneration = ++generation;
    setConnection('connecting');
    status('Autenticando e preparando o navegador…');
    const abort = new AbortController();
    const timeout = setTimeout(() => abort.abort(), 20000);
    try {
      sessionRequest = fetch('/api/session', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token }), signal: abort.signal
      });
      const response = await sessionRequest;
      token = '';
      const config = await response.json().catch(() => ({}));
      if (generation !== currentGeneration) return;
      if (!response.ok) throw new Error(config.message || `Não foi possível conectar (${response.status}).`);
      applyConfig(config);
      openSocket(currentGeneration);
    } catch (error) {
      if (generation !== currentGeneration) return;
      setConnection('closing');
      await logout();
      if (generation !== currentGeneration) return;
      setConnection('disconnected');
      status(error.name === 'AbortError' ? 'O servidor demorou para responder. Tente conectar novamente.' : error.message, true);
      ui.token.focus();
    } finally {
      token = '';
      clearTimeout(timeout);
      sessionRequest = null;
    }
  });

  ui.disconnect.addEventListener('click', () => { void disconnect(); });
  ui['address-form'].addEventListener('submit', event => {
    event.preventDefault();
    let url = ui.address.value.trim();
    if (!url) return;
    if (!/^[a-z][a-z0-9+.-]*:/i.test(url)) url = `https://${url}`;
    try {
      const parsed = new URL(url);
      if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password) throw new Error();
      if (send({ type: 'navigate', url: parsed.href })) {
        ui.address.value = parsed.href;
        status('Abrindo página…');
        canvas.focus({ preventScroll: true });
      }
    } catch (_) { status('Use um endereço HTTP ou HTTPS sem usuário e senha.', true); }
  });
  ['back', 'forward', 'reload', 'home'].forEach(type => {
    document.getElementById(type).addEventListener('click', () => {
      if (send({ type })) canvas.focus({ preventScroll: true });
    });
  });

  function point(event) {
    const rect = canvas.getBoundingClientRect();
    return {
      x: Math.max(0, Math.min(canvas.width - 1, Math.round((event.clientX - rect.left) * canvas.width / rect.width))),
      y: Math.max(0, Math.min(canvas.height - 1, Math.round((event.clientY - rect.top) * canvas.height / rect.height)))
    };
  }
  canvas.addEventListener('pointermove', event => {
    if (!active || (event.pointerType === 'touch' && !pressedButtons.has(0))) return;
    lastPoint = pendingMove = point(event);
    if (moveTimer === null) moveTimer = setTimeout(flushMove, 40);
  });
  canvas.addEventListener('pointerdown', event => {
    if (!active || event.button > 2 || !event.isPrimary) return;
    event.preventDefault();
    canvas.focus({ preventScroll: true });
    flushMove();
    lastPoint = point(event);
    if (send({ type: 'pointer', action: 'down', ...lastPoint, button: event.button })) {
      pressedButtons.add(event.button);
      canvas.setPointerCapture(event.pointerId);
    }
  });
  canvas.addEventListener('pointerup', event => {
    if (!pressedButtons.has(event.button)) return;
    event.preventDefault();
    flushMove();
    lastPoint = point(event);
    send({ type: 'pointer', action: 'up', ...lastPoint, button: event.button });
    pressedButtons.delete(event.button);
    if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
  });
  canvas.addEventListener('pointercancel', releaseInput);
  canvas.addEventListener('contextmenu', event => { event.preventDefault(); });
  canvas.addEventListener('wheel', event => {
    if (!active) return;
    event.preventDefault();
    const multiplier = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? canvas.height : 1;
    wheelX = Math.max(-1200, Math.min(1200, wheelX + event.deltaX * multiplier));
    wheelY = Math.max(-1200, Math.min(1200, wheelY + event.deltaY * multiplier));
    if (wheelTimer === null) wheelTimer = setTimeout(() => {
      send({ type: 'wheel', deltaX: Math.round(wheelX), deltaY: Math.round(wheelY) });
      wheelX = wheelY = 0;
      wheelTimer = null;
    }, 40);
  }, { passive: false });

  canvas.addEventListener('keydown', event => {
    if (!active || event.isComposing || event.key === 'Dead' || event.key === 'Process') return;
    // Let the local paste event provide plain text instead of Chromium's clipboard.
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'v') return;
    event.preventDefault();
    if (specialKeys.has(event.key) || ((event.ctrlKey || event.metaKey || event.altKey) && event.key.length === 1)) {
      if (send({ type: 'key', action: 'down', key: event.key })) pressedKeys.add(event.key);
    } else if (event.key.length === 1) {
      send({ type: 'text', text: event.key });
    }
  });
  canvas.addEventListener('keyup', event => {
    if (!active || !pressedKeys.has(event.key)) return;
    event.preventDefault();
    send({ type: 'key', action: 'up', key: event.key });
    pressedKeys.delete(event.key);
  });
  canvas.addEventListener('paste', event => {
    if (!active) return;
    const text = event.clipboardData?.getData('text/plain');
    if (text) { event.preventDefault(); send({ type: 'text', text: text.slice(0, 1024).replace(/[\uD800-\uDBFF]$/, '') }); }
  });
  canvas.addEventListener('blur', releaseInput);
  window.addEventListener('blur', releaseInput);
  ui['text-form'].addEventListener('submit', event => {
    event.preventDefault();
    const text = ui['remote-text'].value;
    if (text && send({ type: 'text', text })) {
      ui['remote-text'].value = '';
      status('Texto enviado para o campo selecionado no navegador.');
    }
  });
  window.addEventListener('pagehide', () => {
    if (socket) socket.close(1000, 'Página fechada.');
    void fetch('/api/logout', { method: 'POST', credentials: 'same-origin', body: '', keepalive: true }).catch(() => {});
  });
  fetch('/api/config', { credentials: 'same-origin' })
    .then(response => { if (!response.ok) throw new Error(); return response.json(); })
    .then(applyConfig)
    .catch(() => {
      ui['allowed-domains'].replaceChildren();
      const item = document.createElement('li');
      item.textContent = 'Configuração indisponível.';
      ui['allowed-domains'].append(item);
      status('Não foi possível carregar a configuração do servidor.', true);
    });
})();
