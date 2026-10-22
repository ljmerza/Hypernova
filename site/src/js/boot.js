// Runs before the page paints (it's tiny and cached): marks that scripts run, and which kind of
// device this is, so the install page leads with the right download without anything moving later.
(function (d, n) {
  var h = d.documentElement, ua = n.userAgent || '';
  h.classList.add('js');
  if (/Android/i.test(ua)) h.classList.add('android');
  else if (/Macintosh|Mac OS X/.test(ua) && !(n.maxTouchPoints > 1)) h.classList.add('mac');
  else if (/Linux|X11|CrOS/.test(ua) && !/iPhone|iPad/.test(ua)) h.classList.add('linux');
})(document, navigator);
