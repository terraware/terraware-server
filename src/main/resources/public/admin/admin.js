function rememberSectionExpansions() {
    const detailsElements = document.querySelectorAll('details.section');
    const detailsState = JSON.parse(localStorage.getItem('adminSectionsExpanded')) || {};

    detailsElements.forEach(details => {
        // Expand previously-expanded sections on initial load.
        if (details.id in detailsState) {
            details.open = detailsState[details.id];
        }

        // As sections are toggled, update local storage to reflect their states.
        details.addEventListener('toggle', () => {
            detailsState[details.id] = details.open;
            localStorage.setItem('adminSectionsExpanded', JSON.stringify(detailsState));
        });
    });
}

// Expand previously-opened sections on page load.
document.addEventListener('DOMContentLoaded', rememberSectionExpansions);
