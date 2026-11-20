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

    function select(index) {
      if (matches.length === 0) return;
      current = (index + matches.length) % matches.length;
      document.querySelectorAll('.dg-search-current').forEach(node => node.classList.remove('dg-search-current'));
      const node = matches[current];
      node.classList.add('dg-search-current');
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
