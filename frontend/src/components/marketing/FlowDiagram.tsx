import { useEffect, useState } from 'react';

import { Icon } from '../ui/Icon';
import { cn } from '../ui/primitives';

const STAGES = [
  {
    icon: <Icon.User size={16} />,
    label: 'Your career profile',
    note: 'Skills, experience, the roles you actually want.',
  },
  {
    icon: <Icon.Radar size={16} />,
    label: 'Source intelligence',
    note: 'Where should we look? May we? Is it healthy?',
  },
  {
    icon: <Icon.Layers size={16} />,
    label: 'Job intelligence',
    note: 'What is this role? Has it changed? Have we seen it before?',
  },
  {
    icon: <Icon.Sparkle size={16} />,
    label: 'Candidate intelligence',
    note: 'How well does it fit you, and why?',
  },
  {
    icon: <Icon.Check size={16} />,
    label: 'Relevant opportunities',
    note: 'With the reasoning attached.',
  },
];

/**
 * The product in five steps, with a signal travelling down it.
 *
 * The animation is the argument: CareerFlux is a pipeline, not a search box.
 * It advances one stage at a time and stops moving entirely under
 * prefers-reduced-motion, where the diagram still reads correctly as a
 * static list.
 */
export function FlowDiagram() {
  const [active, setActive] = useState(0);
  const [animate, setAnimate] = useState(true);

  useEffect(() => {
    const query = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (query?.matches) {
      setAnimate(false);
      setActive(-1);
      return;
    }
    const timer = window.setInterval(() => {
      setActive((current) => (current + 1) % (STAGES.length + 1));
    }, 1400);
    return () => window.clearInterval(timer);
  }, []);

  return (
    <div className="flow" aria-label="How CareerFlux works, in five stages">
      {STAGES.map((stage, index) => (
        <div key={stage.label}>
          <div className={cn('flow__stage', animate && index === active && 'flow__stage--live')}>
            <span className="flow__icon">{stage.icon}</span>
            <div>
              <p className="flow__label">{stage.label}</p>
              <p className="flow__note">{stage.note}</p>
            </div>
          </div>
          {index < STAGES.length - 1 && (
            <div className={cn('flow__connector', animate && index === active && 'flow__connector--live')}>
              <Icon.ArrowDown size={14} />
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
