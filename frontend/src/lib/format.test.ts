import { describe, expect, it } from 'vitest';

import { salaryLabel } from './format';

/**
 * How money is written on the screen.
 *
 * <p>CareerFlux is used by students at Indian colleges, and Indian postings quote
 * pay in lakhs per annum. Rendering ₹1,000,000 as "₹1000k" is not merely
 * unidiomatic — it is a number nobody writes, so a student has to stop and
 * convert it before they can tell whether the role is worth opening. That is the
 * regression these tests exist to catch.
 */
describe('salary formatting', () => {
  describe('rupees', () => {
    it('renders a range in lakhs per annum', () => {
      expect(
        salaryLabel({ min: 600000, max: 900000, currency: 'INR', period: 'YEAR' }),
      ).toBe('₹6–9 LPA');
    });

    it('does not fall back to thousands above ten lakh', () => {
      // The case that made this visible: "₹1000k–₹1400k" on a real posting.
      expect(
        salaryLabel({ min: 1000000, max: 1400000, currency: 'INR', period: 'YEAR' }),
      ).toBe('₹10–14 LPA');
    });

    it('keeps one decimal where a figure is not a whole lakh', () => {
      expect(
        salaryLabel({ min: 450000, max: 600000, currency: 'INR', period: 'YEAR' }),
      ).toBe('₹4.5–6 LPA');
    });

    it('handles a single figure', () => {
      expect(salaryLabel({ min: 800000, max: null, currency: 'INR', period: 'YEAR' })).toBe(
        '₹8 LPA',
      );
    });

    it('says per month when the source said per month', () => {
      // Interns are paid monthly, and calling that LPA would overstate it
      // twelvefold.
      expect(
        salaryLabel({ min: 2500000, max: null, currency: 'INR', period: 'MONTHLY' }),
      ).toBe('₹25 L / month');
    });
  });

  describe('other currencies are untouched', () => {
    it('still renders dollars in thousands', () => {
      expect(
        salaryLabel({ min: 90000, max: 120000, currency: 'USD', period: 'YEAR' }),
      ).toBe('$90k–$120k');
    });

    it('still marks an hourly rate', () => {
      expect(salaryLabel({ min: 45, max: 60, currency: 'USD', period: 'HOURLY' })).toBe(
        '$45–$60 / hour',
      );
    });
  });

  describe('nothing stated', () => {
    it('returns null rather than a zero', () => {
      // Salary is shown only when a source stated it. A "₹0 LPA" would be a
      // claim about the role that nobody made.
      expect(salaryLabel(null)).toBeNull();
      expect(salaryLabel(undefined)).toBeNull();
      expect(salaryLabel({ min: null, max: null, currency: 'INR', period: 'YEAR' })).toBeNull();
    });
  });
});
