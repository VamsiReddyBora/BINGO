import React, { useRef, useEffect } from 'react';
import { InGameChatMessage } from '../types/models';

interface Props {
  messages: InGameChatMessage[];
  onOpenChatDrawer: () => void;
}

export const InGameChatSpace: React.FC<Props> = ({
  messages,
  onOpenChatDrawer
}) => {
  const containerRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom on new message
  useEffect(() => {
    if (containerRef.current) {
      containerRef.current.scrollTo({
        top: containerRef.current.scrollHeight,
        behavior: 'smooth'
      });
    }
  }, [messages.length]);

  return (
    <div
      onClick={onOpenChatDrawer}
      onDoubleClick={onOpenChatDrawer}
      title="Click or double-tap to chat"
      className="w-full max-w-[320px] xs:max-w-[350px] sm:max-w-[400px] lg:max-w-[440px] mx-auto my-1 sm:my-1.5 h-14 sm:h-18 px-1.5 flex flex-col justify-end cursor-pointer group"
    >
      {messages.length === 0 ? (
        <div className="h-full flex items-center justify-center text-slate-400 group-hover:text-purple-600 transition-colors">
          <span className="text-[11px] sm:text-xs font-semibold tracking-wide flex items-center gap-1.5 opacity-60 group-hover:opacity-100">
            <span>💬</span>
            <span>Tap to chat during match</span>
          </span>
        </div>
      ) : (
        <div
          ref={containerRef}
          className="w-full h-full overflow-y-auto no-scrollbar space-y-1.5 pr-0.5"
        >
          {messages.map((msg) => (
            <div
              key={msg.id}
              className={`flex flex-col ${
                msg.isSelf ? 'items-end' : 'items-start'
              } animate-fade-in`}
            >
              {!msg.isSelf && msg.senderName && (
                <span className="text-[9px] font-bold text-slate-500 pl-1 mb-0.5">
                  {msg.senderName}
                </span>
              )}
              <div
                className={`max-w-[85%] px-3 py-1.5 rounded-2xl text-xs sm:text-[13px] font-medium leading-snug shadow-sm select-text ${
                  msg.isSelf
                    ? 'bg-[#DCF8C6] text-slate-800 rounded-br-xs border border-[#C5EBAB]'
                    : 'bg-white text-slate-800 rounded-bl-xs border border-slate-200/90'
                }`}
              >
                {msg.text}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
