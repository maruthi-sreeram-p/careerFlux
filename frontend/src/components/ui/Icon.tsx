import type { SVGProps } from 'react';

/**
 * A small hand-rolled icon set.
 *
 * Every glyph is drawn on the same 24-unit grid with a 1.6 stroke, so they sit
 * together without the visual-weight mismatch you get from mixing icon
 * libraries. Shipping our own also keeps the bundle honest: we carry the twenty
 * or so icons the product uses, not two thousand.
 */

type IconProps = SVGProps<SVGSVGElement> & { size?: number };

function Svg({ size = 16, children, ...props }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...props}
    >
      {children}
    </svg>
  );
}

export const Icon = {
  Dashboard: (props: IconProps) => (
    <Svg {...props}>
      <rect x="3" y="3" width="7" height="9" rx="1.5" />
      <rect x="14" y="3" width="7" height="5" rx="1.5" />
      <rect x="14" y="12" width="7" height="9" rx="1.5" />
      <rect x="3" y="16" width="7" height="5" rx="1.5" />
    </Svg>
  ),
  Compass: (props: IconProps) => (
    <Svg {...props}>
      <circle cx="12" cy="12" r="9" />
      <path d="m15.5 8.5-2 5.5-5.5 2 2-5.5z" />
    </Svg>
  ),
  Radar: (props: IconProps) => (
    <Svg {...props}>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="5" />
      <circle cx="12" cy="12" r="1.2" fill="currentColor" stroke="none" />
      <path d="M12 12 18.4 5.6" />
    </Svg>
  ),
  Bookmark: (props: IconProps) => (
    <Svg {...props}>
      <path d="M6 4.5A1.5 1.5 0 0 1 7.5 3h9A1.5 1.5 0 0 1 18 4.5V21l-6-4-6 4z" />
    </Svg>
  ),
  BookmarkFilled: (props: IconProps) => (
    <Svg {...props} fill="currentColor">
      <path d="M6 4.5A1.5 1.5 0 0 1 7.5 3h9A1.5 1.5 0 0 1 18 4.5V21l-6-4-6 4z" />
    </Svg>
  ),
  User: (props: IconProps) => (
    <Svg {...props}>
      <circle cx="12" cy="8" r="3.75" />
      <path d="M4.5 20.5a7.5 7.5 0 0 1 15 0" />
    </Svg>
  ),
  Bell: (props: IconProps) => (
    <Svg {...props}>
      <path d="M18 8.5a6 6 0 1 0-12 0c0 5-2 6.5-2 6.5h16s-2-1.5-2-6.5" />
      <path d="M13.7 19a2 2 0 0 1-3.4 0" />
    </Svg>
  ),
  Search: (props: IconProps) => (
    <Svg {...props}>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.6-3.6" />
    </Svg>
  ),
  Filter: (props: IconProps) => (
    <Svg {...props}>
      <path d="M3 5h18l-7 8v6l-4 2v-8z" />
    </Svg>
  ),
  Check: (props: IconProps) => (
    <Svg {...props}>
      <path d="m4.5 12.5 5 5 10-11" />
    </Svg>
  ),
  Triangle: (props: IconProps) => (
    <Svg {...props}>
      <path d="M12 4.5 21 19.5H3z" />
    </Svg>
  ),
  Close: (props: IconProps) => (
    <Svg {...props}>
      <path d="M6 6 18 18M18 6 6 18" />
    </Svg>
  ),
  ChevronRight: (props: IconProps) => (
    <Svg {...props}>
      <path d="m9 5 7 7-7 7" />
    </Svg>
  ),
  ChevronLeft: (props: IconProps) => (
    <Svg {...props}>
      <path d="m15 5-7 7 7 7" />
    </Svg>
  ),
  ChevronDown: (props: IconProps) => (
    <Svg {...props}>
      <path d="m5 9 7 7 7-7" />
    </Svg>
  ),
  ArrowRight: (props: IconProps) => (
    <Svg {...props}>
      <path d="M4 12h16M14 6l6 6-6 6" />
    </Svg>
  ),
  ArrowDown: (props: IconProps) => (
    <Svg {...props}>
      <path d="M12 4v16M6 14l6 6 6-6" />
    </Svg>
  ),
  External: (props: IconProps) => (
    <Svg {...props}>
      <path d="M14 4h6v6" />
      <path d="M20 4 10.5 13.5" />
      <path d="M19 14v5a1.5 1.5 0 0 1-1.5 1.5H5.5A1.5 1.5 0 0 1 4 19V6.5A1.5 1.5 0 0 1 5.5 5H10" />
    </Svg>
  ),
  Upload: (props: IconProps) => (
    <Svg {...props}>
      <path d="M12 16V4" />
      <path d="m7 9 5-5 5 5" />
      <path d="M4 16v2.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V16" />
    </Svg>
  ),
  Document: (props: IconProps) => (
    <Svg {...props}>
      <path d="M14 3H7.5A1.5 1.5 0 0 0 6 4.5v15A1.5 1.5 0 0 0 7.5 21h9a1.5 1.5 0 0 0 1.5-1.5V7z" />
      <path d="M14 3v4h4" />
    </Svg>
  ),
  Shield: (props: IconProps) => (
    <Svg {...props}>
      <path d="M12 3 4.5 6v6c0 4.5 3.2 7.9 7.5 9 4.3-1.1 7.5-4.5 7.5-9V6z" />
      <path d="m9 12 2 2 4-4" />
    </Svg>
  ),
  Pulse: (props: IconProps) => (
    <Svg {...props}>
      <path d="M3 12h4l2.5-7 5 14L17 12h4" />
    </Svg>
  ),
  Layers: (props: IconProps) => (
    <Svg {...props}>
      <path d="m12 3 9 5-9 5-9-5z" />
      <path d="m3 13 9 5 9-5" />
    </Svg>
  ),
  Sliders: (props: IconProps) => (
    <Svg {...props}>
      <path d="M4 7h10M18 7h2M4 17h4M12 17h8" />
      <circle cx="16" cy="7" r="2" />
      <circle cx="10" cy="17" r="2" />
    </Svg>
  ),
  Building: (props: IconProps) => (
    <Svg {...props}>
      <path d="M4 21V6.5A1.5 1.5 0 0 1 5.5 5h6A1.5 1.5 0 0 1 13 6.5V21" />
      <path d="M13 11h5.5A1.5 1.5 0 0 1 20 12.5V21" />
      <path d="M3 21h18M7 9h2M7 13h2M7 17h2M16 15h1M16 18h1" />
    </Svg>
  ),
  Pin: (props: IconProps) => (
    <Svg {...props}>
      <path d="M12 21s7-5.5 7-11a7 7 0 1 0-14 0c0 5.5 7 11 7 11" />
      <circle cx="12" cy="10" r="2.5" />
    </Svg>
  ),
  Clock: (props: IconProps) => (
    <Svg {...props}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5.5l3.5 2" />
    </Svg>
  ),
  Refresh: (props: IconProps) => (
    <Svg {...props}>
      <path d="M20 11A8 8 0 0 0 6.3 6.3L4 8.5" />
      <path d="M4 4.5v4h4" />
      <path d="M4 13a8 8 0 0 0 13.7 4.7L20 15.5" />
      <path d="M20 19.5v-4h-4" />
    </Svg>
  ),
  Sparkle: (props: IconProps) => (
    <Svg {...props}>
      <path d="M12 3.5 13.8 9l5.5 1.8-5.5 1.8L12 18l-1.8-5.4L4.7 10.8 10.2 9z" />
      <path d="M18.5 15.5 19.3 18l2.2.8-2.2.8-.8 2.4-.8-2.4-2.2-.8 2.2-.8z" />
    </Svg>
  ),
  Logout: (props: IconProps) => (
    <Svg {...props}>
      <path d="M10 4H6.5A1.5 1.5 0 0 0 5 5.5v13A1.5 1.5 0 0 0 6.5 20H10" />
      <path d="M15 8.5 18.5 12 15 15.5" />
      <path d="M18.5 12H9" />
    </Svg>
  ),
  Menu: (props: IconProps) => (
    <Svg {...props}>
      <path d="M4 7h16M4 12h16M4 17h16" />
    </Svg>
  ),
  Info: (props: IconProps) => (
    <Svg {...props}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5" />
      <circle cx="12" cy="8" r="0.8" fill="currentColor" stroke="none" />
    </Svg>
  ),
  Warning: (props: IconProps) => (
    <Svg {...props}>
      <path d="M12 4 21 19.5H3z" />
      <path d="M12 10v4" />
      <circle cx="12" cy="17" r="0.8" fill="currentColor" stroke="none" />
    </Svg>
  ),
  Plus: (props: IconProps) => (
    <Svg {...props}>
      <path d="M12 5v14M5 12h14" />
    </Svg>
  ),
  Terminal: (props: IconProps) => (
    <Svg {...props}>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="m7.5 9.5 3 2.5-3 2.5M13 15h4" />
    </Svg>
  ),
  Flow: (props: IconProps) => (
    <Svg {...props}>
      <rect x="3" y="3" width="7" height="5" rx="1.5" />
      <rect x="14" y="16" width="7" height="5" rx="1.5" />
      <path d="M6.5 8v6.5a2 2 0 0 0 2 2h9" />
    </Svg>
  ),
};

export type { IconProps };
