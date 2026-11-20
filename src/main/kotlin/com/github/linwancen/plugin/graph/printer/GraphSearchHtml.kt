package com.github.linwancen.plugin.graph.printer

import com.github.linwancen.plugin.graph.ui.DrawGraphBundle

object GraphSearchHtml {

    fun fragment(): String {
        val search = DrawGraphBundle.message("graph.search")
        val previous = DrawGraphBundle.message("graph.search.previous")
        val next = DrawGraphBundle.message("graph.search.next")
        return """
<style>
  .dg-search-toolbar { position: fixed; top: 0; left: 0; right: 0; z-index: 10; box-sizing: border-box; display: flex; gap: 6px; align-items: center; padding: 6px; background: #f5f5f5; }
  .dg-search-toolbar input { min-width: 180px; padding: 4px; }
  .dg-search-toolbar button { padding: 4px 8px; }
  .dg-search-match > rect, .dg-search-match > polygon, .dg-search-match > ellipse, .dg-search-match > path { stroke: #ff9800 !important; stroke-width: 3px !important; }
  .dg-search-current > rect, .dg-search-current > polygon, .dg-search-current > ellipse, .dg-search-current > path { stroke: #f44336 !important; stroke-width: 5px !important; }
  .dg-selected-method > rect, .dg-selected-method > polygon, .dg-selected-method > ellipse, .dg-selected-method > path { stroke: #1565c0 !important; stroke-width: 5px !important; }
  .dg-related-edge path, .dg-related-edge polygon, path.dg-related-edge { stroke: #e53935 !important; stroke-width: 3px !important; }
  .dg-related-edge text, .dg-related-edge tspan, .dg-related-edge-label, .dg-related-edge-label text, .dg-related-edge-label tspan { fill: #e53935 !important; color: #e53935 !important; font-weight: bold !important; }
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

    function pointNearNode(path, rect) {
      try {
        const length = path.getTotalLength();
        const matrix = path.getScreenCTM();
        if (!length || !matrix) return false;
        const svg = path.ownerSVGElement;
        const near = point => {
          const screenPoint = svg.createSVGPoint();
          screenPoint.x = point.x;
          screenPoint.y = point.y;
          const transformed = screenPoint.matrixTransform(matrix);
          const margin = 16;
          return transformed.x >= rect.left - margin && transformed.x <= rect.right + margin &&
            transformed.y >= rect.top - margin && transformed.y <= rect.bottom + margin;
        };
        return near(path.getPointAtLength(0)) || near(path.getPointAtLength(length));
      } catch (_) {
        return false;
      }
    }

    function highlightEdgeLabel(svg, path, edge) {
      if (edge.matches('g.edgePath')) {
        const edgePaths = Array.from(svg.querySelectorAll('g.edgePath'));
        const edgeLabels = Array.from(svg.querySelectorAll('g.edgeLabel'));
        const index = edgePaths.indexOf(edge);
        if (index >= 0 && edgeLabels[index]) {
          edgeLabels[index].classList.add('dg-related-edge-label');
          return;
        }
      }

      if (edge !== path && edge.querySelector('text')) {
        edge.classList.add('dg-related-edge');
        return;
      }

      try {
        const length = path.getTotalLength();
        const matrix = path.getScreenCTM();
        if (!length || !matrix) return;
        const svgPoint = svg.createSVGPoint();
        const midpoint = path.getPointAtLength(length / 2);
        svgPoint.x = midpoint.x;
        svgPoint.y = midpoint.y;
        const screenPoint = svgPoint.matrixTransform(matrix);
        const labels = Array.from(svg.querySelectorAll('g.edgeLabel, text'))
          .filter(label => !label.closest('.searchMethod'));
        let nearest = null;
        let nearestDistance = 48;
        labels.forEach(label => {
          const rect = label.getBoundingClientRect();
          const dx = rect.left + rect.width / 2 - screenPoint.x;
          const dy = rect.top + rect.height / 2 - screenPoint.y;
          const distance = Math.sqrt(dx * dx + dy * dy);
          if (distance < nearestDistance) {
            nearest = label;
            nearestDistance = distance;
          }
        });
        if (nearest) nearest.classList.add('dg-related-edge-label');
      } catch (_) {
        // SVG text geometry is not available before the browser finishes layout.
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
      const rect = node.getBoundingClientRect();
      const paths = Array.from(svg.querySelectorAll('path')).filter(path => !path.closest('.searchMethod'));
      const highlighted = new Set();

      paths.forEach(path => {
        const edge = path.closest('g.edge, g.edgePath, g.link') || path;
        let connected = pointNearNode(path, rect);
        if (edge.matches('g.edge') && nodeTitle) {
          const edgeTitle = edge.querySelector('title');
          const title = edgeTitle ? edgeTitle.textContent : '';
          connected = title.split('->').some(endpoint => endpoint.trim() === nodeTitle);
        } else if (edge.matches('g.edgePath') && mermaidNodeId) {
          connected = Array.from(edge.classList).some(edgeClass =>
            edgeClass === 'LS-' + mermaidNodeId || edgeClass === 'LE-' + mermaidNodeId
          );
        }
        if (connected) {
          highlighted.add(edge);
          highlightEdgeLabel(svg, path, edge);
        }
      });
      highlighted.forEach(edge => edge.classList.add('dg-related-edge'));
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
