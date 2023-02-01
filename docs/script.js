// Simple JavaScript for navigation smooth scrolling
document.addEventListener('DOMContentLoaded', function() {
    // Smooth scrolling for anchor links
    document.querySelectorAll('a[href^="#"]').forEach(anchor => {
        anchor.addEventListener('click', function (e) {
            e.preventDefault();
            
            const target = document.querySelector(this.getAttribute('href'));
            if (target) {
                window.scrollTo({
                    top: target.offsetTop - 70,
                    behavior: 'smooth'
                });
            }
        });
    });

    // Add scroll effect to navigation
    window.addEventListener('scroll', function() {
        const nav = document.querySelector('nav');
        if (window.scrollY > 100) {
            nav.style.boxShadow = '0 2px 10px rgba(0,0,0,0.1)';
        } else {
            nav.style.boxShadow = 'none';
        }
    });

    // Add animation to feature cards on scroll
    const featureCards = document.querySelectorAll('.feature-card, .module-card');
    
    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.style.opacity = 1;
                entry.target.style.transform = 'translateY(0)';
            }
        });
    }, { threshold: 0.1 });

    featureCards.forEach(card => {
        card.style.opacity = 0;
        card.style.transform = 'translateY(20px)';
        card.style.transition = 'opacity 0.5s ease, transform 0.5s ease';
        observer.observe(card);
    });
});

// GitHub button hover effect
document.addEventListener('DOMContentLoaded', function() {
    const githubButton = document.querySelector('.cta-button a');
    if (githubButton) {
        githubButton.addEventListener('mouseenter', function() {
            this.style.background = 'linear-gradient(135deg, #764ba2 0%, #667eea 100%)';
        });
        
        githubButton.addEventListener('mouseleave', function() {
            this.style.background = 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)';
        });
    }
});