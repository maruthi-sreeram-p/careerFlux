import { useState, type KeyboardEvent } from 'react';

import { Icon } from './Icon';

/**
 * A tag-style multi-value input.
 *
 * Enter and comma commit a value; Backspace on an empty field removes the last
 * one. Values are deduplicated case-insensitively so "Java" and "java" cannot
 * both end up on a profile.
 */
export function TokenInput({
  values,
  onChange,
  placeholder,
  maxValues = 20,
  label,
}: {
  values: string[];
  onChange: (values: string[]) => void;
  placeholder?: string;
  maxValues?: number;
  label?: string;
}) {
  const [draft, setDraft] = useState('');

  const commit = (raw: string) => {
    const value = raw.trim().replace(/,+$/, '').trim();
    if (!value || values.length >= maxValues) {
      setDraft('');
      return;
    }
    const exists = values.some((existing) => existing.toLowerCase() === value.toLowerCase());
    if (!exists) {
      onChange([...values, value]);
    }
    setDraft('');
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault();
      commit(draft);
      return;
    }
    if (event.key === 'Backspace' && draft === '' && values.length > 0) {
      onChange(values.slice(0, -1));
    }
  };

  return (
    <div className="token-input">
      {values.map((value) => (
        <span className="token" key={value}>
          {value}
          <button
            type="button"
            className="token__remove"
            aria-label={`Remove ${value}`}
            onClick={() => onChange(values.filter((entry) => entry !== value))}
          >
            <Icon.Close size={10} />
          </button>
        </span>
      ))}
      <input
        className="token-input__field"
        value={draft}
        aria-label={label}
        placeholder={values.length === 0 ? placeholder : 'Add another…'}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={onKeyDown}
        onBlur={() => commit(draft)}
      />
    </div>
  );
}

/** A multi-select expressed as a grid of toggles rather than a native multiple select. */
export function OptionGrid({
  options,
  selected,
  onChange,
  labelFor,
}: {
  options: string[];
  selected: string[];
  onChange: (values: string[]) => void;
  labelFor: (value: string) => string;
}) {
  const toggle = (value: string) => {
    onChange(
      selected.includes(value)
        ? selected.filter((entry) => entry !== value)
        : [...selected, value],
    );
  };

  return (
    <div className="option-grid">
      {options.map((option) => {
        const isSelected = selected.includes(option);
        return (
          <button
            type="button"
            key={option}
            className={`option${isSelected ? ' option--selected' : ''}`}
            aria-pressed={isSelected}
            onClick={() => toggle(option)}
          >
            <span className="option__check">{isSelected && <Icon.Check size={10} />}</span>
            {labelFor(option)}
          </button>
        );
      })}
    </div>
  );
}
