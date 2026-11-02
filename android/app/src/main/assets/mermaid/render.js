// The chat's mermaid diagrams (ui/components/Mermaid.kt). The app hands this page a diagram's
// source; mermaid draws it here, offline, and the page hands back its SVG and a picture of it.
(() => {
  const native = window.HN || { ready() {}, drawn() {}, failed() {} }
  // The app's colours (docs/DESIGN.md) on mermaid's "base" theme.
  const C = {
    bg: '#060A17', bg950: '#04060F', bg900: '#11141F', bg800: '#181B26', bg700: '#252A3A', lineStrong: '#43444F',
    ink: '#F1E9D6', muted: '#B6B1A2', dim: '#8E8C80', accentDim: '#478F81', danger: '#EE4468',
  }
  // Series colours: the terminal's palette (assets/term/index.html), bright for pie slices and git
  // branches, deep under ink labels for mindmap and timeline sections.
  const BRIGHT = ['#6FE0C2', '#FFA63C', '#8AB4FF', '#D69CFF', '#7FE7FF', '#EE4468', '#B3E8CF', '#F2CC6E', '#AFCBFF', '#E5BDFF', '#A9F0FF', '#FF9C9C']
  const DEEP = ['#24574F', '#5A4618', '#283C5E', '#47305C', '#1E4A52', '#5C2828']
  const series = {}
  BRIGHT.forEach((c, i) => { series['pie' + (i + 1)] = c; if (i < 8) { series['git' + i] = c; series['gitBranchLabel' + i] = C.bg } })
  for (let i = 0; i < 12; i++) { series['cScale' + i] = DEEP[i % DEEP.length]; series['cScaleLabel' + i] = C.ink }
  const FONT = 'Manrope, sans-serif'
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    // A syntax error is thrown, not drawn: the app shows the code instead.
    suppressErrorRendering: true,
    // Labels as SVG text, not HTML: a canvas that drew an SVG's HTML (a foreignObject) can't be read
    // back, so it couldn't become a picture.
    htmlLabels: false,
    flowchart: { htmlLabels: false },
    class: { htmlLabels: false },
    journey: { textPlacement: 'tspan', taskFontFamily: FONT },
    sequence: { actorFontFamily: FONT, messageFontFamily: FONT, noteFontFamily: FONT },
    theme: 'base',
    darkMode: true,
    fontFamily: FONT,
    themeVariables: {
      darkMode: true,
      background: C.bg950,
      fontFamily: FONT,
      fontSize: '14px',
      primaryColor: C.bg800, primaryTextColor: C.ink, primaryBorderColor: C.accentDim,
      secondaryColor: C.bg700, secondaryTextColor: C.ink, secondaryBorderColor: C.lineStrong,
      tertiaryColor: C.bg900, tertiaryTextColor: C.muted, tertiaryBorderColor: C.lineStrong,
      mainBkg: C.bg800, nodeBorder: C.accentDim, lineColor: C.dim, textColor: C.muted, titleColor: C.ink,
      clusterBkg: C.bg900, clusterBorder: C.lineStrong, edgeLabelBackground: C.bg900,
      noteBkgColor: C.bg700, noteTextColor: C.ink, noteBorderColor: C.lineStrong,
      actorBkg: C.bg800, actorBorder: C.accentDim, actorTextColor: C.ink, actorLineColor: C.lineStrong,
      signalColor: C.muted, signalTextColor: C.muted,
      labelBoxBkgColor: C.bg800, labelBoxBorderColor: C.accentDim, labelTextColor: C.ink, loopTextColor: C.muted,
      activationBkgColor: C.bg700, activationBorderColor: C.lineStrong, sequenceNumberColor: C.bg,
      errorBkgColor: C.danger, errorTextColor: C.bg,
      pieStrokeColor: C.bg950, pieOuterStrokeColor: C.lineStrong, pieSectionTextColor: C.bg, pieOpacity: '0.9',
      pieTitleTextColor: C.ink, pieLegendTextColor: C.muted,
      ...series,
    },
    // Journey's section names would take their section's own colour.
    themeCSS: 'text.journey-section { fill: #F1E9D6; }',
  })

  // Manrope as a data: URL: an SVG drawn as a picture can't load a font by its URL.
  let font = ''
  // The longest side and the most pixels a picture gets (the full-screen view draws the SVG itself).
  const MAX_SIDE = 2048
  const MAX_PIXELS = 3000000
  const box = document.getElementById('box')
  const queue = []
  let busy = false
  let n = 0

  async function draw(src, scale) {
    const id = 'm' + (++n)
    try {
      const { svg } = await mermaid.render(id, src)
      box.innerHTML = svg
    } finally {
      document.getElementById('d' + id)?.remove()
    }
    const el = box.querySelector('svg')
    if (!el) throw new Error('no svg')
    const vb = el.viewBox && el.viewBox.baseVal
    let w = vb && vb.width, h = vb && vb.height
    if (!w || !h) { const r = el.getBoundingClientRect(); w = r.width; h = r.height }
    if (!w || !h) throw new Error('empty diagram')
    // Its own size, so it's w × h wherever it goes.
    el.setAttribute('width', String(w))
    el.setAttribute('height', String(h))
    el.style.removeProperty('max-width')
    const plain = new XMLSerializer().serializeToString(el)

    const s = Math.max(0.1, Math.min(scale, MAX_SIDE / w, MAX_SIDE / h, Math.sqrt(MAX_PIXELS / (w * h))))
    const cw = Math.max(1, Math.round(w * s)), ch = Math.max(1, Math.round(h * s))
    el.setAttribute('width', String(cw))
    el.setAttribute('height', String(ch))
    if (font) {
      const style = document.createElementNS('http://www.w3.org/2000/svg', 'style')
      style.textContent = `@font-face { font-family: "Manrope"; src: url(${font}) format("truetype"); font-weight: 200 800; }`
      el.insertBefore(style, el.firstChild)
    }
    const pictured = new XMLSerializer().serializeToString(el)
    box.innerHTML = ''
    const png = await picture(pictured, cw, ch)
    return { svg: plain, width: w, height: h, png }
  }

  function picture(svg, cw, ch) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }))
      const img = new Image()
      img.onload = () => {
        try {
          const canvas = document.createElement('canvas')
          canvas.width = cw
          canvas.height = ch
          canvas.getContext('2d').drawImage(img, 0, 0, cw, ch)
          const data = canvas.toDataURL('image/png')
          resolve(data.slice(data.indexOf(',') + 1))
        } catch (e) {
          reject(e)
        } finally {
          URL.revokeObjectURL(url)
        }
      }
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('picture failed')) }
      img.src = url
    })
  }

  async function next() {
    busy = true
    while (queue.length) {
      const { key, src, scale } = queue.shift()
      try {
        native.drawn(key, JSON.stringify(await draw(src, scale)))
      } catch (e) {
        box.innerHTML = ''
        native.failed(key, String((e && e.message) || e).slice(0, 300))
      }
    }
    busy = false
  }

  window.hn = {
    /** Draws [src]; the answer comes back as HN.drawn(key, json) or HN.failed(key, why). */
    render(key, src, scale) {
      queue.push({ key, src, scale: Number(scale) || 1 })
      if (!busy) next()
    },
  }

  const fontRead = fetch('Manrope.ttf')
    .then((r) => r.ok ? r.blob() : Promise.reject(new Error('no font')))
    .then((b) => new Promise((resolve) => { const f = new FileReader(); f.onload = () => resolve(f.result); f.onerror = () => resolve(''); f.readAsDataURL(b) }))
    .then((u) => { font = u })
  Promise.all([document.fonts.load('500 14px Manrope'), fontRead]).catch(() => {}).finally(() => native.ready())
})()
