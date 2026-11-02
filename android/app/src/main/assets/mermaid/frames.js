// Frames on a timer: the page that draws diagrams is never seen (1 px and clear), so frames may not
// come, and some of mermaid's layouts (d3, cytoscape) wait for one.
window.requestAnimationFrame = (f) => setTimeout(() => f(performance.now()), 16)
window.cancelAnimationFrame = (id) => clearTimeout(id)
// A WebView too old for mermaid or render.js (a syntax error, a missing call) never gets window.hn:
// the page still says it's ready and answers every diagram as failed, so the app shows the code at once.
window.addEventListener('load', function () {
  if (window.hn || !window.HN) return
  window.hn = { render: function (key) { window.HN.failed(key, 'webview too old') } }
  window.HN.ready()
})
