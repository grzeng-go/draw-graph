package com.github.linwancen.plugin.graph.printer

import com.github.linwancen.plugin.graph.ui.DrawGraphBundle

object GraphSearchHtml {

    fun fragment(selectedMethod: String? = null): String {
        val search = DrawGraphBundle.message("graph.search")
        val previous = DrawGraphBundle.message("graph.search.previous")
        val next = DrawGraphBundle.message("graph.search.next")
        val selectedMethodLiteral = selectedMethod?.let {
            "\"" + it
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("<", "\\u003c") + "\""
        } ?: "null"
        return """
<style>
  .dg-search-toolbar { position: fixed; top: 0; left: 0; right: 0; z-index: 10; box-sizing: border-box; display: flex; gap: 6px; align-items: center; padding: 6px; background: #f5f5f5; }
  .dg-search-toolbar input { min-width: 180px; padding: 4px; }
  .dg-search-toolbar button { padding: 4px 8px; }
  .dg-search-match > rect, .dg-search-match > polygon, .dg-search-match > ellipse, .dg-search-match > path { stroke: #ff9800 !important; stroke-width: 3px !important; }
  .dg-search-current > rect, .dg-search-current > polygon, .dg-search-current > ellipse, .dg-search-current > path { stroke: #f44336 !important; stroke-width: 5px !important; }
  .dg-selected-method { filter: drop-shadow(0 0 6px #1565c0); }
  .dg-selected-method > rect, .dg-selected-method > polygon, .dg-selected-method > ellipse, .dg-selected-method > path { stroke: #1565c0 !important; stroke-width: 5px !important; }
  .dg-related-edge path, .dg-related-edge polygon, path.dg-related-edge { stroke: #e53935 !important; stroke-width: 3px !important; }
  .dg-related-edge text, .dg-related-edge tspan, .dg-related-edge-label, .dg-related-edge-label text, .dg-related-edge-label tspan { fill: #e53935 !important; color: #e53935 !important; stroke: #e53935 !important; font-weight: bold !important; }
</style>
<div class="dg-search-toolbar">
  <input id="dg-search-input" type="search" placeholder="$search" aria-label="$search">
  <button id="dg-search-previous" type="button">$previous</button>
  <button id="dg-search-next" type="button">$next</button>
  <span id="dg-search-count"></span>
</div>
<script>
  (function() {
    const input = document.getElementById('dg-search-input');
    const count = document.getElementById('dg-search-count');
    let matches = [];
    let current = -1;

    function highlightEdgeLabel(svg, path, edge) {
      if (edge.matches('g.edge')) {
        const labels = edge.querySelectorAll('text, tspan');
        if (labels.length) {
          labels.forEach(label => label.classList.add('dg-related-edge-label'));
          return;
        }
      }

      if (edge.matches('g.edgePath')) {
        const edgePaths = Array.from(svg.querySelectorAll('g.edgePath'));
        const edgeLabels = Array.from(svg.querySelectorAll('g.edgeLabel'));
        const index = edgePaths.indexOf(edge);
        const label = edgeLabels[index];
        if (label) {
          label.classList.add('dg-related-edge-label');
          return;
        }
      }

      if (edge.matches('g[id^="link_"]')) {
        edge.querySelectorAll('text, tspan').forEach(label => label.classList.add('dg-related-edge-label'));
      }
    }

    function highlightRelated(node) {
      document.querySelectorAll('.dg-selected-method').forEach(item => item.classList.remove('dg-selected-method'));
      document.querySelectorAll('.dg-related-edge').forEach(item => item.classList.remove('dg-related-edge'));
      document.querySelectorAll('.dg-related-edge-label').forEach(item => item.classList.remove('dg-related-edge-label'));
      node.classList.add('dg-selected-method');

      const svg = node.ownerSVGElement;
      if (!svg) return;
      const nodeTitleElement = node.querySelector('title');
      const nodeTitle = nodeTitleElement ? nodeTitleElement.textContent.trim() : null;
      const flowchartId = node.id.match(/^flowchart-(.*)-\d+$/);
      const mermaidNodeId = flowchartId ? flowchartId[1] : null;
      const plantUmlNodeId = node.id.startsWith('elem_') ? node.id.substring(5) : null;
      const paths = Array.from(svg.querySelectorAll('path')).filter(path => !path.closest('.searchMethod'));
      const highlighted = new Set();

      paths.forEach(path => {
        const edge = path.closest('g.edge, g.edgePath, g.link, g[id^="link_"]') || path;
        let connected = false;
        if (edge.matches('g.edge') && nodeTitle) {
          const edgeTitle = edge.querySelector('title');
          const title = edgeTitle ? edgeTitle.textContent : '';
          connected = title.split('->').some(endpoint => endpoint.trim() === nodeTitle);
        } else if (edge.matches('g.edgePath') && mermaidNodeId) {
          connected = Array.from(edge.classList).some(edgeClass =>
            edgeClass === 'LS-' + mermaidNodeId || edgeClass === 'LE-' + mermaidNodeId
          );
        } else if (edge.matches('g[id^="link_"]') && plantUmlNodeId) {
          const edgeId = edge.id;
          connected = edgeId.startsWith('link_' + plantUmlNodeId + '_') ||
            edgeId.endsWith('_' + plantUmlNodeId);
        }
        if (connected) highlighted.add(edge);
      });
      highlighted.forEach(edge => edge.classList.add('dg-related-edge'));
      paths.forEach(path => {
        const edge = path.closest('g.edge, g.edgePath, g.link, g[id^="link_"]') || path;
        if (highlighted.has(edge)) highlightEdgeLabel(svg, path, edge);
      });
    }

    function selectRequestedMethod() {
      const requestedName = $selectedMethodLiteral;
      if (!requestedName) return false;
      const normalize = value => value.replace(/\s+/g, ' ').trim().toLocaleLowerCase();
      const normalizedName = normalize(requestedName);
      const candidates = Array.from(document.querySelectorAll('.searchMethod'))
        .filter(node => normalize(node.textContent).includes(normalizedName))
        .sort((left, right) => left.textContent.length - right.textContent.length);
      if (!candidates.length) return false;
      const node = candidates[0];
      requestAnimationFrame(() => {
        highlightRelated(node);
        node.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' });
      });
      return true;
    }

    if ($selectedMethodLiteral !== null) {
      let attempts = 0;
      let retryTimer = null;
      const stopSelecting = () => {
        observer.disconnect();
        if (retryTimer !== null) clearTimeout(retryTimer);
      };
      const observer = new MutationObserver(() => {
        if (selectRequestedMethod()) stopSelecting();
      });
      observer.observe(document.documentElement, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['class']
      });
      const retrySelection = () => {
        if (selectRequestedMethod()) {
          stopSelecting();
        } else if (++attempts < 60) {
          retryTimer = setTimeout(retrySelection, 100);
        } else {
          stopSelecting();
        }
      };
      retrySelection();
    }

    document.addEventListener('click', event => {
      const target = event.target instanceof Element ? event.target.closest('.searchMethod') : null;
      if (target) highlightRelated(target);
    }, true);

    function select(index) {
      if (matches.length === 0) return;
      current = (index + matches.length) % matches.length;
      document.querySelectorAll('.dg-search-current').forEach(node => node.classList.remove('dg-search-current'));
      const node = matches[current];
      node.classList.add('dg-search-current');
      highlightRelated(node);
      node.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' });
      count.textContent = (current + 1) + ' / ' + matches.length;
    }

    function update() {
      const query = input.value.trim().toLocaleLowerCase();
      matches = query ? Array.from(document.querySelectorAll('.searchMethod')).filter(
        node => node.textContent.toLocaleLowerCase().includes(query)
      ) : [];
      document.querySelectorAll('.dg-search-match').forEach(node => node.classList.remove('dg-search-match', 'dg-search-current'));
      matches.forEach(node => node.classList.add('dg-search-match'));
      current = -1;
      if (matches.length) select(0);
      else count.textContent = '0 / 0';
    }

    input.addEventListener('input', update);
    input.addEventListener('keydown', event => {
      if (event.key === 'Enter') select(current + (event.shiftKey ? -1 : 1));
      if (event.key === 'Escape') {
        input.value = '';
        update();
      }
    });
    document.getElementById('dg-search-previous').addEventListener('click', () => select(current - 1));
    document.getElementById('dg-search-next').addEventListener('click', () => select(current + 1));
  })();
</script>
"""
    }
}
