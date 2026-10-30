// Frames on a timer: the page that draws diagrams is never seen (1 px and clear), so frames may not
// come, and some of mermaid's layouts (d3, cytoscape) wait for one.
window.requestAnimationFrame = (f) => setTimeout(() => f(performance.now()), 16)
window.cancelAnimationFrame = (id) => clearTimeout(id)
